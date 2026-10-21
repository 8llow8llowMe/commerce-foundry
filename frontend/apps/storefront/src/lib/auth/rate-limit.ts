import { ApiError } from '@cf/api-client';
import { fill } from '@/lib/i18n/format';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 429 요청 제한 안내 문구. 이유는 서버 코드로 고르고(AUTH_040 로그인 · AUTH_041 가입, 그 밖은 일반 문구),
 * 남은 시간은 `Retry-After` 초로 붙인다 — 1분 이상이면 분 단위로 올려 보인다(15분 창이 "840초"로 보이지 않게).
 * 버튼 쿨다운(`useRetryCooldown`)은 최대 60초라 이 시간보다 짧을 수 있다. 다시 누르면 서버가 남은 시간을 다시 알려 준다.
 */
export function rateLimitText(
  code: string | null,
  retryAfterSec: number | null,
  t: Dictionary['auth'],
): string {
  const { rateLimit } = t;
  const reason = (code && rateLimit.reasons[code]) || rateLimit.other;
  if (retryAfterSec === null || retryAfterSec <= 0) return `${reason} ${rateLimit.retryLater}`;
  const wait =
    retryAfterSec >= 60
      ? fill(rateLimit.minutes, { n: Math.ceil(retryAfterSec / 60) })
      : fill(rateLimit.seconds, { n: retryAfterSec });
  return `${reason} ${fill(rateLimit.retryIn, { wait })}`;
}

/** 요청 제한(429)이면 안내 문구, 아니면 null */
export function rateLimitTextOf(cause: unknown, t: Dictionary['auth']): string | null {
  if (!(cause instanceof ApiError) || cause.kind !== 'rateLimited') return null;
  return rateLimitText(cause.code, cause.retryAfterSec, t);
}

/** 주소로 받은 남은 시간의 표시 상한(초) — 손으로 만든 주소가 터무니없는 시간을 보이지 않게 */
export const MAX_LINKED_RETRY_AFTER_SEC = 3600;

/**
 * 가입 직후 로그인 화면 주소(`?signedUp=1&limited=<초>&reason=<코드>`)에서 이어받을 요청 제한을 읽는다(`signedUpLoginPath`).
 * 가입 완료 안내가 아니면 무시한다. 초는 1 ~ 상한의 정수만 받고 그 밖은 모름(null), 코드는 사전에 있는 것만 받는다.
 */
export function readLoginRateLimit(
  params: { signedUp?: string | string[]; limited?: string | string[]; reason?: string | string[] },
  t: Dictionary['auth'],
): { code: string | null; retryAfterSec: number | null } | null {
  const { signedUp, limited, reason } = params;
  if (signedUp !== '1' || typeof limited !== 'string') return null;
  const sec = /^\d{1,6}$/.test(limited) ? Number(limited) : 0;
  return {
    code: typeof reason === 'string' && t.rateLimit.reasons[reason] ? reason : null,
    retryAfterSec: sec > 0 && sec <= MAX_LINKED_RETRY_AFTER_SEC ? sec : null,
  };
}
