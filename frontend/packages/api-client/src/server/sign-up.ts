import { isApiEnvelope } from '../envelope';
import { parseRetryAfter } from '../retry-after';
import { isSameOrigin } from './proxy';
import {
  attemptLogin,
  crossSite,
  forwardedHeaders,
  noStore,
  relayFailure,
  unavailable,
  type LoginHandlerOptions,
} from './login';
import { joinUrl } from './url';

export const SIGN_UP_PATH = '/api/v1/auth/sign-up';

/**
 * BFF 가입 응답 dataBody. 회원 아이디는 화면에 쓰지 않아 넘기지 않는다.
 * 자동 로그인이 요청 제한(429)에 걸렸으면 `loginRateLimit` 에 그 코드(AUTH_040 · GATEWAY_006 등)와 `Retry-After`(초, 없으면 null)를
 * 알려 로그인 화면이 같은 안내 · 쿨다운을 보이게 한다. 그 밖에는 null 이다.
 */
export type SignUpResult = {
  signedIn: boolean;
  loginRateLimit: { code: string | null; retryAfterSec: number | null } | null;
};

/**
 * 브라우저 → Next `POST /api/v1/auth/sign-up` → 게이트웨이 가입(201 `{memberId}`, 토큰 없음) → 같은 이메일 · 비밀번호로 로그인.
 * 가입 실패(검증 fieldErrors · 409 AUTH_002 · 429 AUTH_041 등)는 봉투와 상태를(429 면 Retry-After 까지) 그대로 중계한다.
 * 가입이 성공하면 로그인 결과와 무관하게 201 이다 — 자동 로그인에 실패해도 계정은 이미 만들어졌으므로 가입 실패로 보이지 않게
 * `signedIn: false` 로 알리고, 화면이 로그인 화면으로 보낸다. 비밀번호는 이 요청 안에서만 쓰고 어디에도 남기지 않는다.
 */
export function createSignUpHandler(
  options: LoginHandlerOptions,
): (request: Request) => Promise<Response> {
  const login = {
    gatewayUrl: options.gatewayUrl,
    cookies: options.cookies,
    fetch: options.fetch ?? fetch,
  };

  return async (request) => {
    // 가입 직후 로그인 쿠키를 심는다 — 로그인 핸들러와 같은 이유(login CSRF)로 같은 origin 만
    if (!isSameOrigin(request, new URL(request.url))) return crossSite();

    const raw = await request.text();
    const headers = forwardedHeaders(request, options.clientIpHeader);

    let upstream: Response;
    let payload: unknown;
    try {
      upstream = await login.fetch(joinUrl(options.gatewayUrl, SIGN_UP_PATH), {
        method: 'POST',
        headers,
        body: raw,
        redirect: 'manual',
        cache: 'no-store',
      });
      payload = await upstream.json();
    } catch {
      return unavailable();
    }
    if (!isApiEnvelope(payload)) return unavailable();
    if (!upstream.ok || !payload.dataHeader.success) return relayFailure(upstream, payload);

    const credentials = readCredentials(raw);
    const attempt = credentials
      ? await attemptLogin(login, headers, JSON.stringify(credentials))
      : null;
    const body: SignUpResult = {
      signedIn: attempt?.ok === true,
      loginRateLimit:
        attempt?.ok === false && attempt.response.status === 429
          ? {
              code: attempt.resultCode,
              retryAfterSec: parseRetryAfter(attempt.response.headers.get('retry-after')),
            }
          : null,
    };
    const response = noStore(
      Response.json({ dataHeader: payload.dataHeader, dataBody: body }, { status: 201 }),
    );
    if (attempt?.ok) {
      for (const cookie of attempt.cookies) response.headers.append('set-cookie', cookie);
    }
    return response;
  };
}

/** 가입이 받아들인 본문에서 로그인에 쓸 두 칸만 꺼낸다. BE 가 이미 검증했으니 모양만 본다 */
function readCredentials(raw: string): { email: string; password: string } | null {
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null) return null;
    const { email, password } = value as Record<string, unknown>;
    return typeof email === 'string' && typeof password === 'string' ? { email, password } : null;
  } catch {
    return null;
  }
}
