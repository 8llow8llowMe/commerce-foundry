import { useCallback, useEffect, useState } from 'react';
import { cooldownMsFor, cooldownMsForSec } from './retry-cooldown';

/**
 * 429 rateLimited 를 받은 버튼을 Retry-After 동안 막는다.
 * 게이트웨이 한도는 정책(장바구니 쓰기 · 주문 쓰기)마다 따로라서 버튼(기능)마다 하나씩 쓴다.
 *
 * ```tsx
 * const cooldown = useRetryCooldown();
 * try { await apiFetch(...) } catch (e) { if (!cooldown.startFrom(e)) throw e; setNotice((e as ApiError).message); }
 * <Button disabled={pending || cooldown.coolingDown}>장바구니 담기</Button>
 * ```
 *
 * 다른 화면에서 받은 429 를 이어받을 때는(가입 직후 자동 로그인이 막혀 로그인 화면으로 온 경우) `initial` 로
 * 처음부터 막힌 상태로 시작한다. `retryAfterSec` 이 null 이면 기본값만큼이다.
 */
export function useRetryCooldown(initial?: { retryAfterSec: number | null }): {
  coolingDown: boolean;
  /** 429 면 쿨다운을 시작하고 true, 아니면 아무것도 하지 않고 false 다. */
  startFrom: (error: unknown) => boolean;
} {
  const [until, setUntil] = useState<number | null>(() =>
    initial ? Date.now() + cooldownMsForSec(initial.retryAfterSec) : null,
  );

  useEffect(() => {
    if (until === null) return;
    const timer = setTimeout(() => setUntil(null), Math.max(0, until - Date.now()));
    return () => clearTimeout(timer);
  }, [until]);

  const startFrom = useCallback((error: unknown) => {
    const ms = cooldownMsFor(error);
    if (ms === 0) return false;
    // 쿨다운 중에 또 받으면 더 늦은 시각을 쓴다(줄이지 않는다).
    const next = Date.now() + ms;
    setUntil((prev) => (prev === null ? next : Math.max(prev, next)));
    return true;
  }, []);

  return { coolingDown: until !== null, startFrom };
}
