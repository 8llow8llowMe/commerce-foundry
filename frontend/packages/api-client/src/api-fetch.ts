import { ApiError } from './api-error';
import { unwrap } from './unwrap';

export type ApiFetchInit = Omit<RequestInit, 'body'> & {
  /** JSON 으로 직렬화할 본문 */
  json?: unknown;
  /** 쓰기 요청 멱등 키. 같은 사용자 의도의 재시도에는 같은 값을 쓴다. */
  idempotencyKey?: string;
};

/**
 * 브라우저에서 같은 origin 의 BFF(/api/v1/**)를 부른다.
 * 토큰은 httpOnly 쿠키라 여기서 다루지 않는다. 재발급도 BFF 가 한다.
 */
export async function apiFetch<T>(
  path: string,
  init: ApiFetchInit = {},
  fetchImpl: typeof fetch = fetch,
): Promise<T> {
  const { json, idempotencyKey, headers, ...rest } = init;
  const merged = new Headers(headers);
  merged.set('accept', 'application/json');
  if (json !== undefined) merged.set('content-type', 'application/json');
  if (idempotencyKey) merged.set('idempotency-key', idempotencyKey);

  // 화면 이탈 등으로 취소된 요청은 오류 안내 대상이 아니다. 취소 reason 을 그대로 던진다.
  // 응답 헤더를 받은 뒤 본문을 읽는 중에 취소돼도 마찬가지다.
  const signal = rest.signal;
  try {
    const response = await fetchImpl(path, {
      ...rest,
      headers: merged,
      credentials: 'same-origin',
      body: json === undefined ? undefined : JSON.stringify(json),
    }).catch((cause: unknown) => {
      if (signal?.aborted || isAbortError(cause)) throw cause;
      throw new ApiError('network', null, null, null);
    });
    return await unwrap<T>(response);
  } catch (cause) {
    if (signal?.aborted) throw signal.reason;
    throw cause;
  }
}

function isAbortError(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}
