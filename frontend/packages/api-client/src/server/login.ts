import { errorEnvelope, isApiEnvelope } from '../envelope';
import type { AuthCookieConfig } from './cookies';
import { issueCookies, isSameOrigin } from './proxy';
import { joinUrl } from './url';

export type LoginHandlerOptions = {
  gatewayUrl: string;
  cookies: AuthCookieConfig;
  fetch?: typeof fetch;
};

export const LOGIN_PATH = '/api/v1/auth/login';

/** 게이트웨이 로그인 dataBody(#4 · PR #99). 수명은 초 단위 */
type LoginTokens = {
  accessToken: string;
  accessTokenExpiresIn: number | null;
  refreshToken: string;
  refreshTokenExpiresIn: number | null;
};

// 로그인 요청에서 게이트웨이로 넘기는 헤더. 쿠키 · 인증 헤더는 넘기지 않는다
const FORWARD_HEADERS = ['content-type', 'accept-language'];

/**
 * 브라우저 → Next `POST /api/v1/auth/login` → 게이트웨이. BE 는 토큰을 응답 본문으로 주므로(Set-Cookie 없음)
 * BFF 가 받아 httpOnly 쿠키로 심고, 브라우저에는 토큰을 뺀 성공 봉투(dataBody null)만 돌려준다 — JS 가 토큰을 보지 않는다.
 * 실패(400 AUTH_001 · 검증 fieldErrors 등)는 게이트웨이 봉투와 상태를 그대로 중계한다.
 * 일반 BFF 프록시(`[...path]`)보다 구체적인 경로라 이 핸들러가 먼저 받는다.
 */
export function createLoginHandler(
  options: LoginHandlerOptions,
): (request: Request) => Promise<Response> {
  const fetchImpl = options.fetch ?? fetch;

  return async (request) => {
    // 로그인 성공은 쿠키를 심는다 — 다른 사이트가 사용자 브라우저에 공격자 계정을 심지 못하게(login CSRF) 같은 origin 만
    if (!isSameOrigin(request, new URL(request.url))) {
      return noStore(
        Response.json(errorEnvelope('BFF_003', '허용되지 않은 요청입니다.'), { status: 403 }),
      );
    }

    const headers = new Headers({ accept: 'application/json' });
    for (const name of FORWARD_HEADERS) {
      const value = request.headers.get(name);
      if (value !== null) headers.set(name, value);
    }

    let upstream: Response;
    let payload: unknown;
    try {
      upstream = await fetchImpl(joinUrl(options.gatewayUrl, LOGIN_PATH), {
        method: 'POST',
        headers,
        body: await request.arrayBuffer(),
        redirect: 'manual',
        cache: 'no-store',
      });
      payload = await upstream.json();
    } catch {
      return unavailable();
    }
    if (!isApiEnvelope(payload)) return unavailable();

    if (!upstream.ok || !payload.dataHeader.success) {
      return noStore(Response.json(payload, { status: upstream.status }));
    }

    const tokens = readTokens(payload.dataBody);
    // 성공이라면서 토큰이 없으면 계약 불일치 — 로그인된 척하지 않는다
    if (!tokens) return unavailable();

    const response = noStore(
      Response.json({ dataHeader: payload.dataHeader, dataBody: null }, { status: 200 }),
    );
    const cookies = issueCookies(options.cookies, tokens, {
      access: tokens.accessTokenExpiresIn ?? undefined,
      refresh: tokens.refreshTokenExpiresIn ?? undefined,
    });
    for (const cookie of cookies) response.headers.append('set-cookie', cookie);
    return response;
  };
}

function readTokens(body: unknown): LoginTokens | null {
  if (typeof body !== 'object' || body === null) return null;
  const value = body as Record<string, unknown>;
  if (typeof value.accessToken !== 'string' || typeof value.refreshToken !== 'string') return null;
  const seconds = (input: unknown) =>
    typeof input === 'number' && Number.isInteger(input) && input > 0 ? input : null;
  return {
    accessToken: value.accessToken,
    accessTokenExpiresIn: seconds(value.accessTokenExpiresIn),
    refreshToken: value.refreshToken,
    refreshTokenExpiresIn: seconds(value.refreshTokenExpiresIn),
  };
}

function unavailable(): Response {
  return noStore(
    Response.json(
      errorEnvelope('BFF_002', '서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      { status: 503 },
    ),
  );
}

/** 로그인 응답은 캐시하지 않는다(BE 도 no-store) */
function noStore(response: Response): Response {
  response.headers.set('cache-control', 'no-store');
  return response;
}
