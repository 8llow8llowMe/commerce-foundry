import { ApiError } from '@cf/api-client';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 장바구니 API 실패를 화면 문구로. 서버 코드로 사전 문구를 고르고(영문 스토어에서도 같은 뜻), 없으면 한 문구.
 * 429 는 게이트웨이 요청 제한(GATEWAY_006) — 버튼은 useRetryCooldown 으로 Retry-After 동안 막는다.
 */
export function cartErrorText(error: unknown, t: Dictionary['cart']): string {
  if (error instanceof ApiError) {
    if (error.kind === 'rateLimited') return t.errors.GATEWAY_006 ?? t.failed;
    if (error.code && t.errors[error.code]) return t.errors[error.code]!;
  }
  return t.failed;
}
