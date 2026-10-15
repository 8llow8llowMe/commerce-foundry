import { isApiEnvelope } from '../envelope';
import { joinUrl } from './url';

export type TokenPair = {
  accessToken: string;
  refreshToken: string;
};

export const REISSUE_PATH = '/api/v1/auth/token/reissue';

// 같은 refresh 토큰으로 동시에 들어온 재발급은 한 번만 보낸다(인스턴스 내부 한정).
// 다중 인스턴스 경합은 BE refresh 회전 정책과 함께 #25 에서 정한다.
const inFlight = new Map<string, Promise<TokenPair | null>>();

/**
 * 요청 `{ refreshToken }` → dataBody `{ accessToken, refreshToken }` 은 #4 기준 가정이다.
 * #4 가 머지되면 실제 계약에 맞춘다. 실패는 모두 null 이다(호출자가 쿠키를 지운다).
 */
export function reissueTokens(
  gatewayUrl: string,
  refreshToken: string,
  fetchImpl: typeof fetch,
): Promise<TokenPair | null> {
  const running = inFlight.get(refreshToken);
  if (running) return running;

  const task = requestReissue(gatewayUrl, refreshToken, fetchImpl).finally(() => {
    inFlight.delete(refreshToken);
  });
  inFlight.set(refreshToken, task);
  return task;
}

async function requestReissue(
  gatewayUrl: string,
  refreshToken: string,
  fetchImpl: typeof fetch,
): Promise<TokenPair | null> {
  try {
    const response = await fetchImpl(joinUrl(gatewayUrl, REISSUE_PATH), {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify({ refreshToken }),
      cache: 'no-store',
    });
    if (!response.ok) return null;
    const payload: unknown = await response.json();
    if (!isApiEnvelope(payload) || !payload.dataHeader.success) return null;
    const body = payload.dataBody as Partial<TokenPair> | null;
    if (typeof body?.accessToken !== 'string' || typeof body.refreshToken !== 'string') return null;
    return { accessToken: body.accessToken, refreshToken: body.refreshToken };
  } catch {
    return null;
  }
}
