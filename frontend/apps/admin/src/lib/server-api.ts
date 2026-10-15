import { gatewayRequest } from '@cf/api-client/server';
import { cookies } from 'next/headers';
import { authCookies } from './auth-cookies';
import { gatewayUrl } from './env';

/** Server Component 용. 재발급하지 않는다 — 401 이면 ApiError(unauthorized) 를 던진다. */
export async function serverFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const store = await cookies();
  return gatewayRequest<T>(path, {
    gatewayUrl,
    accessToken: store.get(authCookies.accessName)?.value ?? null,
    init,
  });
}
