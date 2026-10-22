import type { ApiEnvelope } from '../envelope';
import { resolveClientIp, setClientIp } from './client-ip';
import { readCookie, readRefreshCookie, type AuthCookieConfig } from './cookies';
import { crossSite, noStore } from './login';
import { clearCookies, isSameOrigin } from './proxy';
import { reissueTokens } from './reissue';
import { joinUrl } from './url';

export type LogoutHandlerOptions = {
  gatewayUrl: string;
  cookies: AuthCookieConfig;
  fetch?: typeof fetch;
  /** 클라이언트 IP 를 읽을 헤더(`ProxyOptions.clientIpHeader` 와 같다) */
  clientIpHeader?: string | null;
};

export const LOGOUT_PATH = '/api/v1/auth/logout';

/**
 * 브라우저 → Next `POST /api/v1/auth/logout` → 게이트웨이 로그아웃(Bearer + `{ refreshToken }`, BE PR #99).
 * refresh 토큰은 httpOnly 쿠키라 일반 프록시로는 본문에 실을 수 없어 전용 핸들러가 받는다.
 * access 가 만료됐으면(401 · 쿠키 없음) 재발급한 뒤 한 번 다시 부른다 — 그러지 않으면 서버의 refresh family 가 지워지지 않고 남는다.
 * 서버 결과와 무관하게 쿠키를 지우고 200 이다: 사용자가 로그아웃을 눌렀으면 이 브라우저는 로그아웃돼야 한다.
 * 서버 쪽 정리가 실패해도(503 AUTH_013 · 연결 실패) access 는 길어야 수명만큼, refresh 는 이 브라우저에서 사라진다.
 * refresh 쿠키가 없는 설정(어드민)이면 본문 `{}` 으로 access 만 막고(BE `LogoutRequest.refreshToken` 은 선택), 재발급하지 않는다.
 */
export function createLogoutHandler(
  options: LogoutHandlerOptions,
): (request: Request) => Promise<Response> {
  const fetchImpl = options.fetch ?? fetch;
  const { cookies } = options;

  return async (request) => {
    // 다른 사이트가 사용자를 몰래 로그아웃시키지 못하게 같은 origin 만
    if (!isSameOrigin(request, new URL(request.url))) return crossSite();

    const cookieHeader = request.headers.get('cookie');
    const accessToken = readCookie(cookieHeader, cookies.accessName);
    const refreshToken = readRefreshCookie(cookieHeader, cookies);
    const clientIp = resolveClientIp(request, options.clientIpHeader);

    const send = (access: string, refresh: string | null) =>
      fetchImpl(joinUrl(options.gatewayUrl, LOGOUT_PATH), {
        method: 'POST',
        headers: setClientIp(
          new Headers({
            accept: 'application/json',
            'content-type': 'application/json',
            authorization: `Bearer ${access}`,
          }),
          clientIp,
        ),
        body: JSON.stringify(refresh ? { refreshToken: refresh } : {}),
        redirect: 'manual',
        cache: 'no-store',
      });

    try {
      const first = accessToken ? await send(accessToken, refreshToken) : null;
      if (first) await first.body?.cancel();
      if ((first === null || first.status === 401) && refreshToken) {
        const result = await reissueTokens(options.gatewayUrl, refreshToken, fetchImpl, clientIp);
        // 재발급이 회전시켰으니 새 refresh 로 family 를 지운다. 거절이면 이미 끝난 세션이다
        if (result.status === 'issued') {
          const retried = await send(result.pair.accessToken, result.pair.refreshToken);
          await retried.body?.cancel();
        }
      }
    } catch {
      // 서버 정리 실패는 아래에서 쿠키를 지우는 것으로 갈음한다
    }

    const body: ApiEnvelope<null> = {
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: null,
    };
    const response = noStore(Response.json(body, { status: 200 }));
    for (const cookie of clearCookies(cookies)) response.headers.append('set-cookie', cookie);
    return response;
  };
}
