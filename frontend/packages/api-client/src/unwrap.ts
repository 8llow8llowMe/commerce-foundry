import { ApiError, classifyFailure } from './api-error';
import { isApiEnvelope } from './envelope';

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

  throw new ApiError(
    classifyFailure(response.status, dataHeader),
    response.status,
    dataHeader.resultCode,
    dataHeader.resultMessage,
    dataHeader.fieldErrors ?? [],
  );
}
