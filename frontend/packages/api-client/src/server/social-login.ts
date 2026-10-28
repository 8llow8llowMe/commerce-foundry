import { errorEnvelope } from '../envelope';
import { isSocialProvider, type SocialProvider } from '../social-provider';
import { issueCookies } from './proxy';
import { readTokenPair } from './reissue';
import { joinUrl } from './url';
import { loginHandler, noStore, requestLogin, type LoginHandlerOptions } from './login';

/** 게이트웨이 소셜 로그인 경로. 제공자는 허용 목록 값만 들어온다 */
export function socialLoginPath(provider: SocialProvider): string {
  return `/api/v1/auth/oauth/${provider}/login`;
}

/** Next route handler 의 두 번째 인자(`RouteContext<'/api/v1/auth/oauth/[provider]/login'>`)와 같은 모양 */
export type SocialLoginContext = { params: Promise<{ provider: string }> };

/**
 * 브라우저 → Next `POST /api/v1/auth/oauth/{provider}/login` → 게이트웨이 소셜 로그인(BE #5).
 * 본문 `{ code, state, verifier, agreements? }` 를 그대로 넘기고, 응답은 이메일 로그인과 같은 토큰 쌍이라
 * `createLoginHandler` 처럼 httpOnly 쿠키로 심고 토큰 없는 성공 봉투만 돌려준다. 같은 origin 요청만 받는다(login CSRF).
 * 실패(AUTH_05x · 409 AUTH_002 · 429 등)는 게이트웨이 봉투 · 상태 · Retry-After 를 그대로 중계한다.
 * 제공자는 kakao · naver 만 — 그 밖은 게이트웨이를 부르지 않고 404 `BFF_001` 이다.
 */
export function createSocialLoginHandler(
  options: LoginHandlerOptions,
): (request: Request, context: SocialLoginContext) => Promise<Response> {
  const fetchImpl = options.fetch ?? fetch;
  return async (request, context) => {
    const { provider } = await context.params;
    if (!isSocialProvider(provider)) return unknownProvider();
    const handle = loginHandler(options, (headers, body) =>
      requestLogin(
        fetchImpl,
        joinUrl(options.gatewayUrl, socialLoginPath(provider)),
        headers,
        body,
        (data) => {
          const tokens = readTokenPair(data);
          return tokens ? issueCookies(options.cookies, tokens) : null;
        },
      ),
    );
    return handle(request);
  };
}

function unknownProvider(): Response {
  return noStore(
    Response.json(errorEnvelope('BFF_001', '존재하지 않는 경로입니다.'), { status: 404 }),
  );
}
