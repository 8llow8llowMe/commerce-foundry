import { ApiError } from '@cf/api-client';

/** Retry-After 가 없거나 읽을 수 없을 때 버튼을 막는 시간(초) */
export const DEFAULT_COOLDOWN_SEC = 3;
/** 잘못된 큰 값으로 버튼이 오래 잠기지 않게 하는 상한(초) */
export const MAX_COOLDOWN_SEC = 60;

/**
 * 요청 제한(429 rateLimited)이면 같은 버튼을 막아 둘 시간(ms)을, 아니면 0 을 돌려준다.
 * 최소 1초는 막는다 — Retry-After: 0 으로 바로 다시 눌러 같은 429 를 받는 것을 피한다.
 */
export function cooldownMsFor(error: unknown): number {
  if (!(error instanceof ApiError) || error.kind !== 'rateLimited') return 0;
  return cooldownMsForSec(error.retryAfterSec);
}

/** Retry-After 초(없으면 null)를 막아 둘 시간(ms)으로 — 기본값 · 최소 1초 · 상한은 위와 같다 */
export function cooldownMsForSec(retryAfterSec: number | null): number {
  const sec = retryAfterSec ?? DEFAULT_COOLDOWN_SEC;
  return Math.min(Math.max(sec, 1), MAX_COOLDOWN_SEC) * 1000;
}
