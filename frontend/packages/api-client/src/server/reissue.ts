import { isApiEnvelope } from '../envelope';
import { setClientIp } from './client-ip';
import { joinUrl } from './url';

export type TokenPair = {
  accessToken: string;
  refreshToken: string;
  /** 서버가 준 수명(초). 없으면 쿠키는 앱 설정 수명을 쓴다 */
  accessTokenExpiresIn?: number;
  refreshTokenExpiresIn?: number;
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
 * 응답 수명(`*ExpiresIn`, 초)은 `TokenPair` 에 실어 쿠키 수명으로 쓴다(`issueCookies`) — 로그인과 같다.
 * `clientIp` 는 게이트웨이행 `X-Forwarded-For` 로 간다(`setClientIp`). 같은 refresh 로 겹친 요청은 먼저 온 요청의 IP 로 한 번만 보낸다.
 */
export function reissueTokens(
  gatewayUrl: string,
  refreshToken: string,
  fetchImpl: typeof fetch,
  clientIp: string | null,
): Promise<ReissueResult> {
  const now = Date.now();
  for (const [key, entry] of recent) {
    if (entry.expiresAt <= now) recent.delete(key);
  }
  const cached = recent.get(refreshToken);
  if (cached) return Promise.resolve(cached.result);

  const running = inFlight.get(refreshToken);
  if (running) return running;

  const task = requestReissue(gatewayUrl, refreshToken, fetchImpl, clientIp)
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
  clientIp: string | null,
): Promise<ReissueResult> {
  try {
    const headers = new Headers({ 'content-type': 'application/json', accept: 'application/json' });
    const response = await fetchImpl(joinUrl(gatewayUrl, REISSUE_PATH), {
      method: 'POST',
      headers: setClientIp(headers, clientIp),
      body: JSON.stringify({ refreshToken }),
      cache: 'no-store',
    });
    if (response.status >= 500) return { status: 'unavailable' };
    const payload: unknown = await response.json().catch(() => null);
    if (!isApiEnvelope(payload)) return { status: 'unavailable' };
    if (!response.ok || !payload.dataHeader.success) return { status: 'rejected' };
    const pair = readTokenPair(payload.dataBody);
    return pair ? { status: 'issued', pair } : { status: 'unavailable' };
  } catch {
    return { status: 'unavailable' };
  }
}

/**
 * 로그인 · 재발급 dataBody `{ accessToken, refreshToken, accessTokenExpiresIn?, refreshTokenExpiresIn? }`(BE PR #99, 수명은 초)를 읽는다.
 * 토큰이 없으면 null(계약 불일치), 수명은 양의 정수일 때만 싣는다.
 */
export function readTokenPair(body: unknown): TokenPair | null {
  if (typeof body !== 'object' || body === null) return null;
  const value = body as Record<string, unknown>;
  if (typeof value.accessToken !== 'string' || typeof value.refreshToken !== 'string') return null;
  const pair: TokenPair = { accessToken: value.accessToken, refreshToken: value.refreshToken };
  const access = lifetime(value.accessTokenExpiresIn);
  const refresh = lifetime(value.refreshTokenExpiresIn);
  if (access !== undefined) pair.accessTokenExpiresIn = access;
  if (refresh !== undefined) pair.refreshTokenExpiresIn = refresh;
  return pair;
}

function lifetime(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined;
}
