import { ApiError } from '../api-error';
import { unwrap } from '../unwrap';
import { joinUrl } from './url';

export type GatewayRequestOptions = {
  gatewayUrl: string;
  accessToken?: string | null;
  init?: RequestInit;
  fetch?: typeof fetch;
};

/**
 * Server Component 에서 게이트웨이를 직접 부른다(자기 BFF 를 거치지 않는다).
 * RSC 는 쿠키를 쓸 수 없어 재발급하지 않는다. 401 이면 unauthorized 를 던지고 호출자가 로그인으로 보낸다.
 */
export async function gatewayRequest<T>(path: string, options: GatewayRequestOptions): Promise<T> {
  const headers = new Headers(options.init?.headers);
  headers.set('accept', 'application/json');
  if (options.accessToken) headers.set('authorization', `Bearer ${options.accessToken}`);

  let response: Response;
  try {
    response = await (options.fetch ?? fetch)(joinUrl(options.gatewayUrl, path), {
      cache: 'no-store',
      ...options.init,
      headers,
    });
  } catch {
    throw new ApiError('network', null, null, null);
  }
  return unwrap<T>(response);
}
