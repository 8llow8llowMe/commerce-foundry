import { errorEnvelope } from '../envelope';
import { resolveClientIp, setClientIp } from './client-ip';
import { readCookie, readRefreshCookie, serializeCookie, type AuthCookieConfig } from './cookies';
import { reissueTokens, type TokenPair } from './reissue';
import { joinUrl } from './url';

export type ProxyOptions = {
  gatewayUrl: string;
  cookies: AuthCookieConfig;
  fetch?: typeof fetch;
  /** 클라이언트 IP 를 읽을 헤더. 생략하면 `x-real-ip`, null 이면 넘기지 않는다(`resolveClientIp`) */
  clientIpHeader?: string | null;
};

const API_PREFIX = '/api/v1/';
/**
 * 일반 프록시가 받지 않는 인증 경로. 토큰을 응답 본문으로 주거나(로그인 · 재발급) refresh 쿠키가 필요한(로그아웃) 경로는
 * 전용 route handler 만 받는다 — 일반 프록시가 중계하면 토큰이 브라우저 JS 로 간다. 전용 핸들러가 없는 앱(스토어프론트의
 * 관리자 로그인, 어드민의 고객 로그인 · 재발급)에서는 이 목록이 그 경로를 404 로 막는다.
 */
const DEDICATED_AUTH_PATHS = new Set([
  '/api/v1/auth/login',
  '/api/v1/auth/admin/login',
  '/api/v1/auth/token/reissue',
  '/api/v1/auth/logout',
  '/api/v1/auth/sign-up',
]);
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
// 게이트웨이로 넘기는 요청 헤더. Cookie·Authorization·X-Forwarded-* 등은 넘기지 않는다(X-Forwarded-For 는 BFF 가 정한 한 값으로 덮어쓴다).
const FORWARD_REQUEST_HEADERS = ['content-type', 'accept', 'accept-language', 'idempotency-key'];
// 중계하지 않는 응답 헤더. fetch 가 본문을 이미 풀었으므로 인코딩·길이 헤더도 버린다.
const DROP_RESPONSE_HEADERS = new Set([
  'set-cookie',
  'content-encoding',
  'content-length',
  'transfer-encoding',
  'connection',
  'keep-alive',
]);

/**
 * 브라우저 → Next(/api/v1/**) → 게이트웨이 중계 핸들러.
 * httpOnly 쿠키의 access 토큰을 Authorization 으로 바꾸고, 401 이면 한 번 재발급·재시도한다.
 * 401 은 인증 단계 거절이라 서버가 처리하지 않았으므로 쓰기 요청도 재시도가 안전하다.
 * access 쿠키가 없고 refresh 만 있으면(access 쿠키 만료) 보내기 **전에** 재발급한다 — 로그인한 회원의 요청이 토큰 없이 가서
 * 게이트웨이의 비로그인(IP) 요청 제한에 세이지 않게(BE security-guide §7-3, #134).
 */
export function createProxyHandler(options: ProxyOptions): (request: Request) => Promise<Response> {
  const fetchImpl = options.fetch ?? fetch;
  const { cookies } = options;

  return async (request) => {
    const url = new URL(request.url);
    if (!url.pathname.startsWith(API_PREFIX) || isDedicatedAuthPath(url.pathname)) {
      return Response.json(errorEnvelope('BFF_001', '존재하지 않는 경로입니다.'), { status: 404 });
    }

    // 쿠키를 Bearer 로 바꿔 주므로 BFF 가 CSRF 경계다. 안전하지 않은 메서드는 같은 origin 요청만 받는다.
    if (!SAFE_METHODS.has(request.method) && !isSameOrigin(request, url)) {
      return Response.json(errorEnvelope('BFF_003', '허용되지 않은 요청입니다.'), { status: 403 });
    }

    // 재시도에 다시 써야 하므로 본문을 한 번 버퍼에 담는다.
    const body =
      request.method === 'GET' || request.method === 'HEAD'
        ? undefined
        : await request.arrayBuffer();
    const cookieHeader = request.headers.get('cookie');
    const accessToken = readCookie(cookieHeader, cookies.accessName);
    const refreshToken = readRefreshCookie(cookieHeader, cookies);
    const target = joinUrl(options.gatewayUrl, url.pathname + url.search);
    const clientIp = resolveClientIp(request, options.clientIpHeader);

    const send = (token: string | null) =>
      fetchImpl(target, {
        method: request.method,
        headers: setClientIp(forwardHeaders(request.headers, token), clientIp),
        body,
        redirect: 'manual',
        cache: 'no-store',
      });

    let issued: TokenPair | null = null;
    try {
      if (!accessToken && refreshToken) {
        const result = await reissueTokens(options.gatewayUrl, refreshToken, fetchImpl, clientIp);
        if (result.status === 'unavailable') throw new Error('reissue unavailable');
        // 거절이면 끝난 세션이다 — 쿠키를 지우고 비로그인으로 보낸다(공개 조회는 그대로 되고, 회원 API 는 401 이다)
        if (result.status === 'rejected') return relay(await send(null), clearCookies(cookies));
        issued = result.pair;
        // 방금 받은 토큰이 401 이면 다시 재발급해도 같다 — 그대로 중계한다
        return relay(await send(issued.accessToken), issueCookies(cookies, issued));
      }

      const first = await send(accessToken);
      if (first.status !== 401 || !refreshToken) return relay(first, []);

      const result = await reissueTokens(options.gatewayUrl, refreshToken, fetchImpl, clientIp);
      // 명시적 거절만 세션을 끝낸다. 일시 장애로는 쿠키를 지우지 않는다(아래 catch → BFF_002).
      if (result.status === 'rejected') return relay(first, clearCookies(cookies));
      await first.body?.cancel();
      if (result.status === 'unavailable') throw new Error('reissue unavailable');

      issued = result.pair;
      const retried = await send(issued.accessToken);
      return relay(retried, issueCookies(cookies, issued));
    } catch {
      const response = Response.json(
        errorEnvelope('BFF_002', '서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
        { status: 503 },
      );
      // 재발급은 성공했다면 회전된 refresh 토큰을 잃지 않도록 쿠키는 심는다.
      if (issued) {
        for (const cookie of issueCookies(cookies, issued)) {
          response.headers.append('set-cookie', cookie);
        }
      }
      return response;
    }
  };
}

/** 대소문자 · 끝 슬래시 · 퍼센트 인코딩 · 경로 매개변수(;) 변형도 같은 경로로 본다. 디코드할 수 없는 경로는 막는다 */
function isDedicatedAuthPath(pathname: string): boolean {
  let decoded: string;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    return true;
  }
  const normalized = decoded
    .toLowerCase()
    .split(';')[0]!
    .replace(/\/{2,}/g, '/')
    .replace(/\/+$/, '');
  return DEDICATED_AUTH_PATHS.has(normalized);
}

/**
 * 브라우저는 Sec-Fetch-Site 를 보낸다. 없으면(구형 브라우저) Origin 으로 판단하고,
 * 둘 다 없으면 브라우저 요청이 아니므로 통과시킨다(쿠키를 자동으로 실어 오는 주체가 없다).
 */
export function isSameOrigin(request: Request, url: URL): boolean {
  const site = request.headers.get('sec-fetch-site');
  if (site !== null) return site === 'same-origin';
  const origin = request.headers.get('origin');
  if (origin !== null) return origin === url.origin;
  return true;
}

function forwardHeaders(source: Headers, accessToken: string | null): Headers {
  const headers = new Headers();
  for (const name of FORWARD_REQUEST_HEADERS) {
    const value = source.get(name);
    if (value !== null) headers.set(name, value);
  }
  if (accessToken) headers.set('authorization', `Bearer ${accessToken}`);
  return headers;
}

function relay(upstream: Response, setCookies: string[]): Response {
  const headers = new Headers();
  upstream.headers.forEach((value, name) => {
    if (!DROP_RESPONSE_HEADERS.has(name)) headers.set(name, value);
  });
  for (const cookie of setCookies) headers.append('set-cookie', cookie);
  return new Response(upstream.body, { status: upstream.status, headers });
}

/**
 * 토큰 쌍을 쿠키로. 수명은 서버가 준 값(`*ExpiresIn`, 초)이 있으면 그것을, 없으면 앱 설정값을 쓴다.
 * refresh 쿠키가 없는 설정(어드민)이면 access 하나만 심는다 — 그런 설정은 재발급하지 않으므로 이 경로에 오지 않는다.
 */
export function issueCookies(config: AuthCookieConfig, pair: TokenPair): string[] {
  const cookies = [accessCookie(config, pair.accessToken, pair.accessTokenExpiresIn)];
  if (config.refreshName !== null) {
    cookies.push(
      serializeCookie(
        config.refreshName,
        pair.refreshToken,
        pair.refreshTokenExpiresIn ?? config.refreshMaxAgeSec,
        config.secure,
      ),
    );
  }
  return cookies;
}

/** access 쿠키 하나. 수명은 서버 값(`accessTokenExpiresIn`, 초)이 있으면 그것을, 없으면 앱 설정값을 쓴다 */
export function accessCookie(
  config: AuthCookieConfig,
  accessToken: string,
  expiresInSec: number | undefined,
): string {
  return serializeCookie(
    config.accessName,
    accessToken,
    expiresInSec ?? config.accessMaxAgeSec,
    config.secure,
  );
}

export function clearCookies(config: AuthCookieConfig): string[] {
  const cookies = [serializeCookie(config.accessName, '', 0, config.secure)];
  if (config.refreshName !== null) {
    cookies.push(serializeCookie(config.refreshName, '', 0, config.secure));
  }
  return cookies;
}
