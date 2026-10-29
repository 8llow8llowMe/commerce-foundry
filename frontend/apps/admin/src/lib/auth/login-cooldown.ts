import { ApiError } from '@cf/api-client';

/** Retry-After 가 없거나 읽을 수 없을 때 로그인 버튼을 막는 시간(초) */
export const LOGIN_DEFAULT_COOLDOWN_SEC = 3;
/**
 * 로그인 버튼을 막는 상한(초). 관리자 로그인 시도 제한 창(BE 기본 15분)을 다 덮되, 잘못된 큰 값으로 오래 잠기지 않게 한다.
 * 스토어프론트(최대 60초)와 달리 창 전체를 막는다 — 창이 끝나기 전에 다시 보내도 같은 429 라서다.
 */
export const LOGIN_MAX_COOLDOWN_SEC = 3600;

/**
 * 로그인 요청 제한(429 rateLimited)이면 버튼을 막아 둘 초를, 아니면 0 을 돌려준다.
 * 최소 1초는 막는다 — Retry-After: 0 으로 바로 다시 눌러 같은 429 를 받지 않게.
 */
export function loginCooldownSec(error: unknown): number {
  if (!(error instanceof ApiError) || error.kind !== 'rateLimited') return 0;
  const sec = error.retryAfterSec ?? LOGIN_DEFAULT_COOLDOWN_SEC;
  return Math.min(Math.max(sec, 1), LOGIN_MAX_COOLDOWN_SEC);
}

/** 막힌 시각(`until`, ms)까지 남은 초 — 올림, 지났거나 막지 않았으면 0 */
export function remainingSecOf(until: number | null, now: number): number {
  if (until === null) return 0;
  return Math.max(0, Math.ceil((until - now) / 1000));
}

/** 남은 시간 문구 — 1분 이상이면 분으로 올려 보인다(15분 창이 "840초"로 보이지 않게) */
export function formatWait(sec: number): string {
  return sec >= 60 ? `${Math.ceil(sec / 60)}분` : `${sec}초`;
}
