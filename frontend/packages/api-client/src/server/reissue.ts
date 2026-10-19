import { isApiEnvelope } from '../envelope';
import { joinUrl } from './url';

export type TokenPair = {
  accessToken: string;
  refreshToken: string;
};

/**
 * - issued: 새 토큰 쌍
 * - rejected: 서버가 refresh 토큰을 명시적으로 거절했다(4xx 또는 success=false) → 쿠키를 지운다
 * - unavailable: 일시 장애·계약 불일치(5xx, 연결 실패, 봉투 아님, 토큰 누락) → 세션을 지우지 않는다
 */
export type ReissueResult =
  { status: 'issued'; pair: TokenPair } | { status: 'rejected' } | { status: 'unavailable' };

export const REISSUE_PATH = '/api/v1/auth/token/reissue';

/**
 * 재발급 직후 옛 refresh 토큰을 실은 요청(브라우저가 새 쿠키를 반영하기 전의 병렬 요청·다른 탭)이
 * 회전된 refresh 로 다시 재발급을 시도해 로그아웃되지 않도록, 성공 결과를 잠시 재사용한다.
 */
export const REISSUE_GRACE_MS = 30_000;

// 진행 중인 재발급과 유예 시간 안의 성공 결과. 인스턴스 내부 한정이다.
// 다른 인스턴스와의 경합은 BE 가 받는다 — 같은 refresh 로 30초 안에 3회까지 재발급(PR #99).
const inFlight = new Map<string, Promise<ReissueResult>>();
const recent = new Map<string, { result: ReissueResult; expiresAt: number }>();

/**
 * 요청 `{ refreshToken }` → dataBody `{ accessToken, refreshToken, *ExpiresIn }` (BE PR #99 계약).
 * 응답 수명(`*ExpiresIn`)은 아직 읽지 않고 앱 설정 수명으로 쿠키를 심는다 — 로그인 핸들러(login.ts)는 읽는다.
 */
export function reissueTokens(
  gatewayUrl: string,
  refreshToken: string,
  fetchImpl: typeof fetch,
): Promise<ReissueResult> {
  const now = Date.now();
  for (const [key, entry] of recent) {
    if (entry.expiresAt <= now) recent.delete(key);
  }
  const cached = recent.get(refreshToken);
  if (cached) return Promise.resolve(cached.result);

  const running = inFlight.get(refreshToken);
  if (running) return running;

  const task = requestReissue(gatewayUrl, refreshToken, fetchImpl)
    .then((result) => {
      if (result.status === 'issued') {
        recent.set(refreshToken, { result, expiresAt: Date.now() + REISSUE_GRACE_MS });
      }
      return result;
    })
    .finally(() => {
      inFlight.delete(refreshToken);
    });
  inFlight.set(refreshToken, task);
  return task;
}

async function requestReissue(
  gatewayUrl: string,
  refreshToken: string,
  fetchImpl: typeof fetch,
): Promise<ReissueResult> {
  try {
    const response = await fetchImpl(joinUrl(gatewayUrl, REISSUE_PATH), {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify({ refreshToken }),
      cache: 'no-store',
    });
    if (response.status >= 500) return { status: 'unavailable' };
    const payload: unknown = await response.json().catch(() => null);
    if (!isApiEnvelope(payload)) return { status: 'unavailable' };
    if (!response.ok || !payload.dataHeader.success) return { status: 'rejected' };
    const body = payload.dataBody as Partial<TokenPair> | null;
    if (typeof body?.accessToken !== 'string' || typeof body.refreshToken !== 'string') {
      return { status: 'unavailable' };
    }
    return {
      status: 'issued',
      pair: { accessToken: body.accessToken, refreshToken: body.refreshToken },
    };
  } catch {
    return { status: 'unavailable' };
  }
}
