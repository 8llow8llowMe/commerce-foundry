import { ApiError } from '@cf/api-client';
import { accessDeniedOf } from '@/lib/api-errors';

/**
 * 어드민 상품 삭제 `DELETE /api/v1/admin/products/{productId}`(BE #9, `backend/docs/api-design-guide.md` §2-13).
 * 숨김 · 판매 종료 · 논리 삭제를 한 번에 하고 되돌릴 수 없다(복구 API 없음). 본문 · 버전 · 멱등 키는 없다 —
 * 이미 삭제된 상품도 200 이라 다시 눌러도 같은 결과다. SKU · 재고 · 기존 주문은 그대로 남는다.
 * 삭제한 상품은 어드민 목록 · 상세에서 빠진다(상세는 404) — 그래서 성공하면 목록으로 간다.
 */

/** 동시 변경 충돌. 읽은 뒤 다른 수정 · 삭제가 먼저 반영됐다 — 다시 읽고 다시 누른다 */
export const PRODUCT_DELETE_CONFLICT = 'PRODUCT_010';
const PRODUCT_NOT_FOUND = 'PRODUCT_001';

/** 목록 주소에 붙여 결과를 한 번 알리는 표시. 값은 `DeletedResult` 다 */
export const DELETED_PARAM = 'deleted';
export type DeletedResult = 'done' | 'gone';

export const DELETED_MESSAGES: Record<DeletedResult, string> = {
  done: '상품을 판매 중지하고 삭제했습니다. 고객 화면과 상품 목록에서 빠졌습니다.',
  gone: '이미 삭제되었거나 없는 상품입니다. 목록을 다시 불러왔습니다.',
};

export const DELETE_CONFLICT_MESSAGE =
  '그사이 다른 곳에서 이 상품이 바뀌었습니다. 최신 상태를 다시 불러왔으니 확인한 뒤 다시 삭제해 주세요.';
export const DELETE_UNKNOWN_MESSAGE =
  '삭제 결과를 확인하지 못했습니다. 화면을 다시 불러왔습니다 — 상품이 그대로 보이면 다시 삭제해 주세요.';
export const DELETE_FAILED = '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.';

export const productDeletedHref = (result: DeletedResult) => `/products?${DELETED_PARAM}=${result}`;

/** 목록 주소의 `?deleted=` 값. 아는 값만 받는다 */
export function parseDeletedResult(value: string | string[] | undefined): DeletedResult | null {
  return value === 'done' || value === 'gone' ? value : null;
}

/**
 * 삭제 중 401. 입력은 없지만 다른 쓰기와 같이 화면을 두고 안내한다(`SESSION_EXPIRED_ON_SAVE`, `AccessNotice` 주석).
 * 401 은 서버가 처리하지 않은 요청이라 다시 로그인한 뒤 같은 버튼을 누르면 된다.
 */
export const SESSION_EXPIRED_ON_DELETE =
  '로그인이 만료되어 삭제하지 못했습니다. 새 탭에서 어드민에 다시 로그인한 뒤 다시 삭제해 주세요.';

/** 실패 → 화면이 할 일. 컴포넌트는 이것을 그대로 적용만 한다 */
export type DeleteFailure =
  /** 403 — 삭제 자리에 `AccessNotice` */
  | { kind: 'forbidden' }
  /** 상품이 이미 없다 — 목록으로 */
  | { kind: 'gone' }
  /** 지금 상태를 다시 읽고(`router.refresh`) 문구를 남긴다. 자동으로 다시 지우지 않는다 */
  | { kind: 'reload'; message: string }
  | { kind: 'message'; message: string };

export function productDeleteFailure(cause: unknown): DeleteFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'forbidden') return { kind: 'forbidden' };
  if (denied === 'unauthorized') return { kind: 'message', message: SESSION_EXPIRED_ON_DELETE };
  if (!(cause instanceof ApiError)) return { kind: 'message', message: DELETE_FAILED };
  if (cause.code === PRODUCT_NOT_FOUND) return { kind: 'gone' };
  if (cause.code === PRODUCT_DELETE_CONFLICT) {
    return { kind: 'reload', message: DELETE_CONFLICT_MESSAGE };
  }
  // 502 · 504 — 서버에서 지웠을 수도 있다. 다시 읽으면 지워졌을 때 상세가 404(not-found)로 바뀐다
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', message: DELETE_UNKNOWN_MESSAGE };
  // 그 밖의 4xx(요청 제한 등)는 서버 문구, 5xx · 연결 실패는 일반 문구
  if (cause.status !== null && cause.status < 500 && cause.code) {
    return { kind: 'message', message: cause.message };
  }
  return { kind: 'message', message: DELETE_FAILED };
}
