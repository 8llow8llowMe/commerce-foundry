import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import {
  DESCRIPTION_CHARACTER,
  IMAGE_REQUIRED,
  LOOK_TOO_LONG,
  PRODUCT_COUNT,
  PRODUCT_DUPLICATED,
  SINGLE_LINE_ONLY,
  TITLE_REQUIRED,
} from './form';
import { LOOK_LIMITS } from './types';

// 룩 쓰기 실패 → 화면 처리. 서버 문구(칸 이름이 영문으로 들어 있고, 없는 상품 오류에는 상품 아이디가 실린다)를 그대로 보이지 않고
// 코드별 업무 문구를 쓴다(#305). 코드 정본은 BE `LookErrorCode` · `LookValidationMessage`(`api-design-guide.md` §2-11).

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const LOOK_GONE_NOTICE = '다른 곳에서 삭제된 룩입니다. 목록을 다시 불러왔습니다.';
export const UNKNOWN_OUTCOME_NOTICE =
  '저장 결과를 확인하지 못해 목록을 다시 불러왔습니다. 반영됐는지 확인해 주세요.';
export const BUSY = '다른 관리자가 룩을 바꾸는 중입니다. 잠시 후 다시 시도해 주세요.';
export const LOOK_LIMIT = `룩은 최대 ${LOOK_LIMITS.looks}개까지 만들 수 있습니다. 쓰지 않는 룩을 지운 뒤 다시 시도해 주세요.`;
export const PRODUCT_MISSING = '찾을 수 없는 상품이 있습니다. 그 상품을 빼고 저장해 주세요.';
export const IMAGE_URL_NOT_ALLOWED =
  '쇼핑몰 이미지 저장소에 올린 이미지 주소만 넣을 수 있습니다. 주소를 확인해 주세요.';
export const MEDIA_STORAGE_UNAVAILABLE =
  '이미지 저장소 설정이 맞지 않아 지금은 룩을 저장할 수 없습니다. 개발 담당자에게 문의해 주세요.';
export const ORDER_CHANGED_NOTICE =
  '그사이 다른 곳에서 룩이 추가되거나 삭제되어 순서를 저장하지 못했습니다. 목록을 다시 불러왔으니 순서를 다시 정해 주세요.';
export const DELETE_FAILED = '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const TOGGLE_FAILED =
  '노출을 바꾸지 못했습니다. 수정 화면에서 내용을 확인한 뒤 다시 시도해 주세요.';

/** 요청 검증 코드 → [폼 칸, 문구]. 칸 키는 `LookForm` 의 칸이다 */
const VALIDATION: Record<string, [string, string]> = {
  LOOK_101: ['title', TITLE_REQUIRED],
  LOOK_102: ['title', LOOK_TOO_LONG.title],
  LOOK_103: ['title', SINGLE_LINE_ONLY],
  LOOK_104: ['description', LOOK_TOO_LONG.description],
  LOOK_105: ['description', DESCRIPTION_CHARACTER],
  LOOK_106: ['imageUrl', IMAGE_REQUIRED],
  LOOK_107: ['imageUrl', IMAGE_REQUIRED],
  LOOK_108: ['imageUrl', LOOK_TOO_LONG.imageUrl],
  LOOK_111: ['altText', LOOK_TOO_LONG.altText],
  LOOK_112: ['altText', SINGLE_LINE_ONLY],
  LOOK_114: ['products', PRODUCT_COUNT],
  LOOK_115: ['products', PRODUCT_COUNT],
};

/** 코드를 모르는 칸 오류 → 서버 칸 경로로 폼 칸을 찾는다 */
function formKey(path: string): string {
  if (path === 'title' || path === 'description') return path;
  if (path === 'image.altText') return 'altText';
  if (path.startsWith('image')) return 'imageUrl';
  if (path.startsWith('productIds')) return 'products';
  return 'form';
}

/** 칸 오류만 있으면 저장 버튼 위에 요약을 둔다 — 오류 칸이 화면 밖일 수 있다 */
export function withSummary(errors: FormErrors): FormErrors {
  if (errors.form || Object.keys(errors).length === 0) return errors;
  return { ...errors, form: [CHECK_FIELDS] };
}

export type LookSaveFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 대상이 없어졌거나(404) 결과를 모름(502 · 504) — 목록을 다시 읽고 안내한다 */
  | { kind: 'reload'; notice: string }
  | { kind: 'errors'; errors: FormErrors };

/**
 * 생성 · 수정 실패를 화면 처리로 가른다.
 * - 401 은 폼을 지키고 다시 로그인을 안내한다. 403 은 권한 안내.
 * - fieldErrors 는 칸에, 룩 규칙 오류(`LOOK_002~006`)는 그 칸 · 폼 전체에 업무 문구로 붙인다.
 * - 버전이 없어 마지막 쓰기가 이긴다. 409 는 잠금 대기(`LOOK_008`) · 룩 상한(`LOOK_002`)뿐이다.
 */
export function lookSaveFailure(cause: unknown): LookSaveFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.code === 'LOOK_001') return { kind: 'reload', notice: LOOK_GONE_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const [key, message] = VALIDATION[item.code] ?? [formKey(item.field), CHECK_FIELDS];
      if (!(errors[key] ?? []).includes(message)) errors[key] = [...(errors[key] ?? []), message];
    }
    return { kind: 'errors', errors: withSummary(errors) };
  }

  switch (cause.code) {
    case 'LOOK_002':
      return { kind: 'errors', errors: { form: [LOOK_LIMIT] } };
    case 'LOOK_003':
      return { kind: 'errors', errors: withSummary({ products: [PRODUCT_DUPLICATED] }) };
    case 'LOOK_004':
      return { kind: 'errors', errors: withSummary({ products: [PRODUCT_MISSING] }) };
    case 'LOOK_005':
      return { kind: 'errors', errors: withSummary({ imageUrl: [IMAGE_URL_NOT_ALLOWED] }) };
    case 'LOOK_006':
      return { kind: 'errors', errors: { form: [MEDIA_STORAGE_UNAVAILABLE] } };
    case 'LOOK_008':
      return { kind: 'errors', errors: { form: [BUSY] } };
  }
  // 요청 제한(429)은 기본 문구가 운영자 문구다. 그 밖은 일반 실패 문구(서버 원문을 보이지 않는다)
  if (cause.kind === 'rateLimited') return { kind: 'errors', errors: { form: [cause.message] } };
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}

export type LookRowFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'reload'; notice: string }
  | { kind: 'message'; message: string };

/**
 * 목록 줄의 쓰기(노출 켜기 · 끄기, 삭제) 실패. 401 은 지킬 입력이 없어 읽기처럼 로그인 화면으로 보낸다.
 * 노출 바꾸기는 룩 전체를 다시 보내므로 저장과 같은 코드(이미지 주소 · 저장소 설정)가 올 수 있다.
 */
export function lookRowFailure(cause: unknown, fallback: string): LookRowFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'message', message: fallback };
  if (cause.code === 'LOOK_001') return { kind: 'reload', notice: LOOK_GONE_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };
  if (cause.code === 'LOOK_008') return { kind: 'message', message: BUSY };
  if (cause.code === 'LOOK_006') return { kind: 'message', message: MEDIA_STORAGE_UNAVAILABLE };
  if (cause.kind === 'rateLimited') return { kind: 'message', message: cause.message };
  return { kind: 'message', message: fallback };
}

export type LookOrderFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 룩 집합이 바뀌었거나(400 `LOOK_007`) 결과를 모름 — 다시 읽는다(바꾼 순서는 버린다) */
  | { kind: 'reload'; notice: string }
  /** 바꾼 순서를 그대로 두고 안내한다 — 다시 저장할 수 있다 */
  | { kind: 'message'; message: string };

/** 순서 저장 실패. 401 은 바꾼 순서(입력)를 지키려고 화면에 둔 채 다시 로그인을 안내한다 */
export function lookOrderFailure(cause: unknown): LookOrderFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'message', message: SESSION_EXPIRED_ON_SAVE };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'message', message: SAVE_FAILED };
  if (cause.code === 'LOOK_007') return { kind: 'reload', notice: ORDER_CHANGED_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };
  if (cause.code === 'LOOK_008') return { kind: 'message', message: BUSY };
  if (cause.kind === 'rateLimited') return { kind: 'message', message: cause.message };
  return { kind: 'message', message: SAVE_FAILED };
}
