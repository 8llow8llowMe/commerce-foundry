import { errorEnvelope, isApiEnvelope, type DataHeader } from '../envelope';
import { resolveClientIp, setClientIp } from './client-ip';
import type { AuthCookieConfig } from './cookies';
import { accessCookie, issueCookies, isSameOrigin } from './proxy';
import { readLifetime, readTokenPair } from './reissue';
import { joinUrl } from './url';

export type LoginHandlerOptions = {
  gatewayUrl: string;
  cookies: AuthCookieConfig;
  fetch?: typeof fetch;
  /** 클라이언트 IP 를 읽을 헤더(`ProxyOptions.clientIpHeader` 와 같다) */
  clientIpHeader?: string | null;
};

export const LOGIN_PATH = '/api/v1/auth/login';
export const ADMIN_LOGIN_PATH = '/api/v1/auth/admin/login';

const FORWARD_HEADERS = ['content-type', 'accept-language'];

/**
 * 브라우저 → Next `POST /api/v1/auth/login` → 게이트웨이. BE 는 토큰을 응답 본문으로 주므로(Set-Cookie 없음)
 * BFF 가 받아 httpOnly 쿠키로 심고, 브라우저에는 토큰을 뺀 성공 봉투(dataBody null)만 돌려준다 — JS 가 토큰을 보지 않는다.
 * 실패(400 AUTH_001 · 검증 fieldErrors · 429 AUTH_040 등)는 게이트웨이 봉투와 상태를(429 면 Retry-After 까지) 그대로 중계한다.
 * 일반 BFF 프록시(`[...path]`)보다 구체적인 경로라 이 핸들러가 먼저 받는다.
 */
export function createLoginHandler(
  options: LoginHandlerOptions,
): (request: Request) => Promise<Response> {
  const fetchImpl = options.fetch ?? fetch;
  return loginHandler(options, (headers, body) =>
    attemptLogin(
      { gatewayUrl: options.gatewayUrl, cookies: options.cookies, fetch: fetchImpl },
      headers,
      body,
    ),
  );
}

/**
 * 브라우저 → Next `POST /api/v1/auth/admin/login` → 게이트웨이 관리자 로그인(BE #191). 고객 로그인과 같지만
 * 응답이 `{ accessToken, accessTokenExpiresIn }` 뿐이다(refresh 없음) — access 쿠키 하나만 심는다. 세션은 그 수명이 전부다.
 * 실패(400 AUTH_030 한 문구 · 검증 fieldErrors)는 게이트웨이 봉투와 상태를 그대로 중계한다.
 */
export function createAdminLoginHandler(
  options: LoginHandlerOptions,
): (request: Request) => Promise<Response> {
  const fetchImpl = options.fetch ?? fetch;
  return loginHandler(options, (headers, body) =>
    requestLogin(
      fetchImpl,
      joinUrl(options.gatewayUrl, ADMIN_LOGIN_PATH),
      headers,
      body,
      (data) => {
        const token = readAccessToken(data);
        return token
          ? [accessCookie(options.cookies, token.accessToken, token.accessTokenExpiresIn)]
          : null;
      },
    ),
  );
}

function loginHandler(
  options: LoginHandlerOptions,
  attempt: (headers: Headers, body: BodyInit) => Promise<LoginAttempt>,
): (request: Request) => Promise<Response> {
  return async (request) => {
    // 로그인 성공은 쿠키를 심는다 — 다른 사이트가 사용자 브라우저에 공격자 계정을 심지 못하게(login CSRF) 같은 origin 만
    if (!isSameOrigin(request, new URL(request.url))) return crossSite();

    const result = await attempt(
      forwardedHeaders(request, options.clientIpHeader),
      await request.arrayBuffer(),
    );
    if (!result.ok) return result.response;

    const response = noStore(
      Response.json({ dataHeader: result.dataHeader, dataBody: null }, { status: 200 }),
    );
    for (const cookie of result.cookies) response.headers.append('set-cookie', cookie);
    return response;
  };
}

/** 실패의 `resultCode` 는 게이트웨이 봉투가 있을 때만 있다(연결 실패 · 계약 불일치는 null) */
export type LoginAttempt =
  | { ok: true; dataHeader: DataHeader; cookies: string[] }
  | { ok: false; response: Response; resultCode: string | null };

/**
 * 게이트웨이 로그인을 부르고 토큰을 쿠키 문자열로 바꾼다. 로그인 화면과 가입 직후 자동 로그인(`createSignUpHandler`)이 같이 쓴다.
 * 실패는 브라우저에 그대로 돌려줄 응답 — 게이트웨이 봉투 · 상태 중계, 연결 실패 · 계약 불일치는 BFF_002 503.
 */
export async function attemptLogin(
  options: Required<Omit<LoginHandlerOptions, 'clientIpHeader'>>,
  headers: Headers,
  body: BodyInit,
): Promise<LoginAttempt> {
  return requestLogin(
    options.fetch,
    joinUrl(options.gatewayUrl, LOGIN_PATH),
    headers,
    body,
    (data) => {
      const tokens = readTokenPair(data);
      return tokens ? issueCookies(options.cookies, tokens) : null;
    },
  );
}

/** 로그인 요청을 보내고 성공 dataBody 를 쿠키로 바꾼다. `toCookies` 가 null 이면(토큰 없음) 계약 불일치다 */
async function requestLogin(
  fetchImpl: typeof fetch,
  url: string,
  headers: Headers,
  body: BodyInit,
  toCookies: (dataBody: unknown) => string[] | null,
): Promise<LoginAttempt> {
  let upstream: Response;
  let payload: unknown;
  try {
    upstream = await fetchImpl(url, {
      method: 'POST',
      headers,
      body,
      redirect: 'manual',
      cache: 'no-store',
    });
    payload = await upstream.json();
  } catch {
    return { ok: false, response: unavailable(), resultCode: null };
  }
  if (!isApiEnvelope(payload)) return { ok: false, response: unavailable(), resultCode: null };

  if (!upstream.ok || !payload.dataHeader.success) {
    return {
      ok: false,
      response: relayFailure(upstream, payload),
      resultCode: payload.dataHeader.resultCode,
    };
  }

  const cookies = toCookies(payload.dataBody);
  // 성공이라면서 토큰이 없으면 계약 불일치 — 로그인된 척하지 않는다
  if (!cookies) return { ok: false, response: unavailable(), resultCode: null };

  return { ok: true, dataHeader: payload.dataHeader, cookies };
}

/** 관리자 로그인 dataBody `{ accessToken, accessTokenExpiresIn? }`(BE #191, 수명은 초). 토큰이 없으면 null */
export function readAccessToken(
  body: unknown,
): { accessToken: string; accessTokenExpiresIn?: number } | null {
  if (typeof body !== 'object' || body === null) return null;
  const value = body as Record<string, unknown>;
  if (typeof value.accessToken !== 'string' || value.accessToken === '') return null;
  const expiresIn = readLifetime(value.accessTokenExpiresIn);
  return expiresIn === undefined
    ? { accessToken: value.accessToken }
    : { accessToken: value.accessToken, accessTokenExpiresIn: expiresIn };
}

/**
 * 인증 요청에서 게이트웨이로 넘기는 헤더만 고른다. 쿠키 · 인증 헤더는 넘기지 않고,
 * `X-Forwarded-For` 는 브라우저 값 대신 앞단이 정한 클라이언트 IP 한 값으로 둔다(`resolveClientIp`).
 */
export function forwardedHeaders(request: Request, clientIpHeader?: string | null): Headers {
  const headers = new Headers({ accept: 'application/json' });
  for (const name of FORWARD_HEADERS) {
    const value = request.headers.get(name);
    if (value !== null) headers.set(name, value);
  }
  return setClientIp(headers, resolveClientIp(request, clientIpHeader));
}

export function crossSite(): Response {
  return noStore(
    Response.json(errorEnvelope('BFF_003', '허용되지 않은 요청입니다.'), { status: 403 }),
  );
}

export function unavailable(): Response {
  return noStore(
    Response.json(
      errorEnvelope('BFF_002', '서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      { status: 503 },
    ),
  );
}

/**
 * 게이트웨이 실패 봉투와 상태를 그대로 돌려준다. 봉투를 다시 만들어도 429 의 `Retry-After` 는 함께 넘긴다
 * — 화면 쿨다운(`ApiError.retryAfterSec`)이 그 값을 쓴다. 일반 프록시 `relay()` 와 같다.
 */
export function relayFailure(upstream: Response, payload: unknown): Response {
  const response = noStore(Response.json(payload, { status: upstream.status }));
  const retryAfter = upstream.headers.get('retry-after');
  if (retryAfter !== null) response.headers.set('retry-after', retryAfter);
  return response;
}

/** 인증 응답은 캐시하지 않는다(BE 도 no-store) */
export function noStore(response: Response): Response {
  response.headers.set('cache-control', 'no-store');
  return response;
}
