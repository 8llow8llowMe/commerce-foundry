import { gatewayRequest } from '@cf/api-client/server';
import { cookies } from 'next/headers';
import { locale } from 'next/root-params';
import { authCookies } from './auth-cookies';
import { gatewayUrl } from './env';

/**
 * Server Component 용. 재발급하지 않는다 — 401 이면 ApiError(unauthorized) 를 던진다.
 * 스토어 언어(app/[locale])를 Accept-Language 로 보낸다 — 상품명 등 다국어 응답은 BE(#20)가 이것으로 고른다.
 */
export async function serverFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const store = await cookies();
  const headers = new Headers(init?.headers);
  if (!headers.has('accept-language')) headers.set('accept-language', await locale());
  return gatewayRequest<T>(path, {
    gatewayUrl,
    accessToken: store.get(authCookies.accessName)?.value ?? null,
    init: { ...init, headers },
  });
}
