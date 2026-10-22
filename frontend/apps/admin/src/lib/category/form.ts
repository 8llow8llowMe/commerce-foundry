import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  fieldKey,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import type { Category, CategoryRequest } from './types';

/** 폼 칸은 글자 그대로 둔다. `parentId` 빈 문자열은 최상위다 */
export type CategoryForm = {
  parentId: string;
  name: string;
  displayOrder: string;
  visible: boolean;
};

export const CATEGORY_GONE_NOTICE = '다른 곳에서 지워진 카테고리입니다. 목록을 다시 불러왔습니다.';
export const CATEGORY_UNKNOWN_NOTICE =
  '결과를 확인하지 못해 목록을 다시 불러왔습니다. 반영됐는지 확인해 주세요.';
export const DELETE_FAILED = '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.';

const DISPLAY_ORDER_MAX = 2_147_483_647;

const FORM_FIELDS = new Set(['parentId', 'name', 'displayOrder', 'visible']);
/** 상위 칸에 붙이는 도메인 오류 — 깊이 초과 · 순환 · 없는 상위 */
const PARENT_ERRORS = new Set(['PRODUCT_040', 'PRODUCT_041', 'PRODUCT_044']);
/** 다시 읽어야 하는 오류 — 대상 카테고리가 없다 */
const NOT_FOUND = 'PRODUCT_004';

export function createForm(parentId: string | null, displayOrder: number): CategoryForm {
  return { parentId: parentId ?? '', name: '', displayOrder: String(displayOrder), visible: true };
}

export function editForm(category: Category): CategoryForm {
  return {
    parentId: category.parentId ?? '',
    name: category.name,
    displayOrder: String(category.displayOrder),
    visible: category.visible,
  };
}

/**
 * 폼 → 본문. 노출 순서는 0 이상 정수 글자만 숫자로 바꾸고, 형식이 틀리면 보내지 않고 그 칸에 오류를 붙인다.
 * 빈 칸은 null 로 보내 서버 검증(`PRODUCT_134`)에 맡긴다. 이름은 그대로 보낸다 — 공백 정리 · 길이 · 금지 문자는 서버가 정본이다.
 */
export function categoryRequest(
  form: CategoryForm,
): { ok: true; request: CategoryRequest } | { ok: false; errors: FormErrors } {
  const order = form.displayOrder.trim();
  // 서버 칸은 Integer 다 — 넘는 값은 보내지 않는다
  if (order !== '' && (!/^\d{1,10}$/.test(order) || Number(order) > DISPLAY_ORDER_MAX)) {
    return { ok: false, errors: { displayOrder: ['0 이상의 정수를 넣어 주세요.'] } };
  }
  return {
    ok: true,
    request: {
      parentId: form.parentId === '' ? null : form.parentId,
      name: form.name,
      displayOrder: order === '' ? null : Number(order),
      visible: form.visible,
    },
  };
}

export type SaveFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 404 PRODUCT_004 · 502/504 — 목록을 다시 읽고 안내한다 */
  | { kind: 'reload'; notice: string }
  | { kind: 'errors'; errors: FormErrors };

/**
 * 생성 · 수정 실패를 화면 처리로 가른다.
 * - 401 은 폼을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`). 403 은 권한 안내.
 * - fieldErrors 는 칸에, 깊이 초과 · 순환 · 없는 상위는 상위 칸에(서버 문구), 409 `PRODUCT_045`(다른 변경 처리 중) 등 그 밖의 4xx 는 폼 전체 서버 문구.
 */
export function categorySaveFailure(cause: unknown): SaveFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.code === NOT_FOUND) return { kind: 'reload', notice: CATEGORY_GONE_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: CATEGORY_UNKNOWN_NOTICE };

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const field = fieldKey(item.field);
      const key = FORM_FIELDS.has(field) ? field : 'form';
      errors[key] = [...(errors[key] ?? []), item.message];
    }
    return { kind: 'errors', errors };
  }
  if (cause.code && PARENT_ERRORS.has(cause.code)) {
    return { kind: 'errors', errors: { parentId: [cause.message] } };
  }
  if (cause.status !== null && cause.status < 500 && cause.code) {
    return { kind: 'errors', errors: { form: [cause.message] } };
  }
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}

export type DeleteFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'reload'; notice: string }
  | { kind: 'message'; message: string };

/**
 * 삭제 실패. 하위 있음(409 `PRODUCT_042`) · 연결 상품 있음(409 `PRODUCT_043`) · 다른 변경 처리 중(409 `PRODUCT_045`)은 서버 문구를 그 줄에 보인다.
 * 401 은 읽기와 같이 로그인 화면으로 보낸다 — 잃을 입력이 없다.
 */
export function categoryDeleteFailure(cause: unknown): DeleteFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'message', message: DELETE_FAILED };
  if (cause.code === NOT_FOUND) return { kind: 'reload', notice: CATEGORY_GONE_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: CATEGORY_UNKNOWN_NOTICE };
  if (cause.status !== null && cause.status < 500 && cause.code) {
    return { kind: 'message', message: cause.message };
  }
  return { kind: 'message', message: DELETE_FAILED };
}
