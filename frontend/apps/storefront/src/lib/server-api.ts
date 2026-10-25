import { gatewayRequest } from '@cf/api-client/server';
import { cookies } from 'next/headers';
import { locale } from 'next/root-params';
import { authCookies } from './auth-cookies';
import { gatewayUrl } from './env';

export type ServerFetchOptions = {
  /**
   * 공개 · 사용자 무관 조회(메인 전시 등) — 인증 쿠키를 싣지 않는다. Next 데이터 캐시는 헤더까지 키로 쓰므로
   * 토큰을 실으면 사용자마다 캐시가 갈린다. 회원 API 에는 쓰지 않는다
   */
  anonymous?: boolean;
};

/**
 * Server Component 용. 재발급하지 않는다 — 401 이면 ApiError(unauthorized) 를 던진다.
 * 스토어 언어(app/[locale])를 Accept-Language 로 보낸다 — 상품명 등 다국어 응답은 BE(#20)가 이것으로 고른다.
 */
export async function serverFetch<T>(
  path: string,
  init?: RequestInit,
  options: ServerFetchOptions = {},
): Promise<T> {
  const headers = new Headers(init?.headers);
  if (!headers.has('accept-language')) headers.set('accept-language', await locale());
  const accessToken = options.anonymous
    ? null
    : ((await cookies()).get(authCookies.accessName)?.value ?? null);
  return gatewayRequest<T>(path, {
    gatewayUrl,
    accessToken,
    init: { ...init, headers },
  });
}
