import { ApiError, classifyFailure } from './api-error';
import { isApiEnvelope } from './envelope';
import { parseRetryAfter } from './retry-after';

/** 응답 봉투를 풀어 dataBody 를 돌려주고, 실패면 ApiError 를 던진다. */
export async function unwrap<T>(response: Response): Promise<T> {
  if (response.status === 204) return null as T;

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError('invalidResponse', response.status, null, null);
  }
  if (!isApiEnvelope(payload)) {
    throw new ApiError('invalidResponse', response.status, null, null);
  }

  const { dataHeader, dataBody } = payload;
  if (dataHeader.success && response.ok) return dataBody as T;

  const kind = classifyFailure(response.status, dataHeader);
  throw new ApiError(
    kind,
    response.status,
    dataHeader.resultCode,
    dataHeader.resultMessage,
    dataHeader.fieldErrors ?? [],
    kind === 'rateLimited' ? parseRetryAfter(response.headers.get('retry-after')) : null,
  );
}
