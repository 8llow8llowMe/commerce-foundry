import { useCallback, useEffect, useState } from 'react';
import { loginCooldownSec, remainingSecOf } from './login-cooldown';

/**
 * 관리자 로그인 429(`AUTH_040` · 게이트웨이 `GATEWAY_006`)를 받으면 `Retry-After` 동안 제출을 막고 남은 초를 1초마다 갱신한다(#407).
 * 자동으로 다시 보내지 않는다 — 끝나면 운영자가 다시 누른다.
 */
export function useLoginCooldown(): {
  /** 남은 초. 0 이면 막혀 있지 않다. */
  remainingSec: number;
  /** 429 면 쿨다운을 시작하고 true, 아니면 아무것도 하지 않고 false 다. */
  startFrom: (error: unknown) => boolean;
} {
  const [until, setUntil] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (until === null) return;
    const timer = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= until) setUntil(null);
    }, 1000);
    return () => clearInterval(timer);
  }, [until]);

  const startFrom = useCallback((error: unknown) => {
    const sec = loginCooldownSec(error);
    if (sec === 0) return false;
    const current = Date.now();
    setNow(current);
    // 쿨다운 중에 또 받으면 더 늦은 시각을 쓴다(줄이지 않는다).
    setUntil((prev) => Math.max(prev ?? 0, current + sec * 1000));
    return true;
  }, []);

  return { remainingSec: remainingSecOf(until, now), startFrom };
}
