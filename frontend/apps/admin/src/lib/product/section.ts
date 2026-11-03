import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  fieldKey,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type FormErrors,
} from '@/lib/api-errors';
import { CHECK_FIELDS } from './create';
import { adminProductPath } from './detail';
import { UPDATE_CONFLICT, UPDATE_UNKNOWN, type UpdateFailure } from './update';

// 어드민 상품 섹션 수정(#29 · BE #9 후속) — 기본 정보(`/header`) 밖의 섹션별 PUT · POST 공통.
// 계약 정본은 `backend/docs/api-design-guide.md` §2-13 · "정지 이미지 전체 편집" 과 `backend/docs/product-sku-edit-guide.md`.
// 모든 섹션은 상세의 `version` 을 되돌려 보내는 상품 버전 CAS 로 줄을 선다 — 그사이 다른 섹션 저장 · 삭제가 먼저면 409.
// 응답은 모두 편집용 상세(`ProductDetailAdminResponse`)라 다음 저장은 응답의 `version` 으로 한다. 멱등 키는 없다 —
// 결과를 모르는 실패 뒤 같은 버전으로 다시 보내면 반영됐을 때 409 라 두 번 쓰이지 않는다(그래도 자동으로 다시 보내지 않는다).

export type ProductSection = 'pricing' | 'categories' | 'images' | 'options' | 'custom-order';

/** 섹션 수정 화면 주소. 기본 정보는 `productEditHref`(`/edit`) 다 */
export const productSectionHref = (productId: string, section: ProductSection) =>
  `/products/${productId}/edit/${section}`;

export const productPricingPath = (productId: string) => `${adminProductPath(productId)}/pricing`;
export const productCategoriesPath = (productId: string) =>
  `${adminProductPath(productId)}/categories`;
export const productImagesPath = (productId: string) => `${adminProductPath(productId)}/images`;
export const productOptionsPath = (productId: string) => `${adminProductPath(productId)}/options`;
/** `PUT` 은 기존 SKU 전체 교체, `POST` 는 옵션 값 · 새 조합 SKU 추가(201) */
export const productSkusPath = (productId: string) => `${adminProductPath(productId)}/skus`;
export const optionRetirementsPath = (productId: string) =>
  `${adminProductPath(productId)}/option-retirements`;
export const productCustomOrderPath = (productId: string) =>
  `${adminProductPath(productId)}/custom-order`;

/**
 * 버전이 어긋났다 — 상품 섹션은 `PRODUCT_010`, SKU · 옵션 은퇴는 `SKU_ADMIN_002`.
 * `SKU_ADMIN_005`(잠금 시간 초과 · 교착)도 "다시 조회한 뒤 시도" 라 같은 처리다.
 */
const CONFLICT_CODES = new Set(['PRODUCT_010', 'SKU_ADMIN_002', 'SKU_ADMIN_005']);
/** 없거나 삭제된 상품 */
const GONE_CODES = new Set(['PRODUCT_001', 'SKU_ADMIN_001']);

export type SectionFailureOptions = {
  /** 이 폼에 칸이 있는 키인가(`fieldKey` 로 바꾼 뒤). 아니면 폼 전체 오류로 모은다 */
  isField: (key: string) => boolean;
  /** 검증 코드 → 화면 문구. 없으면 서버 문구, 그것도 없으면 `CHECK_FIELDS` */
  validation?: Readonly<Record<string, string>>;
};

/**
 * 섹션 저장 실패 → 화면 처리. 폼 입력은 언제나 지킨다. 자동으로 다시 보내지 않는다.
 * 충돌 · 결과 모름은 "최신 내용 불러오기" 를 함께 낸다(`reload`). 업무 규칙 위반(가격 · 조합 등 400)은 서버 문구 그대로다.
 */
export function sectionFailure(cause: unknown, options: SectionFailureOptions): UpdateFailure {
  const errorsOnly = (errors: FormErrors, reload = false): UpdateFailure => ({
    kind: 'errors',
    errors,
    reload,
  });
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return errorsOnly({ form: [SESSION_EXPIRED_ON_SAVE] });
  if (denied) return { kind: 'forbidden' };
  if (!(cause instanceof ApiError)) return errorsOnly({ form: [UPDATE_UNKNOWN] }, true);

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const path = fieldKey(item.field);
      const key = options.isField(path) ? path : 'form';
      const message = options.validation?.[item.code] ?? (item.message || CHECK_FIELDS);
      errors[key] = [...new Set([...(errors[key] ?? []), message])];
    }
    return errorsOnly(errors);
  }
  if (cause.kind === 'notFound' || (cause.code !== null && GONE_CODES.has(cause.code))) {
    return { kind: 'gone' };
  }
  if (cause.code !== null && CONFLICT_CODES.has(cause.code)) {
    return errorsOnly({ form: [UPDATE_CONFLICT] }, true);
  }
  const known = cause.code ? options.validation?.[cause.code] : undefined;
  if (known) return errorsOnly({ form: [known] });
  if (
    cause.kind === 'network' ||
    cause.kind === 'invalidResponse' ||
    cause.kind === 'unknownOutcome' ||
    cause.kind === 'unavailable' ||
    (cause.status !== null && cause.status >= 500)
  ) {
    return errorsOnly({ form: [UPDATE_UNKNOWN] }, true);
  }
  if (cause.status !== null && cause.status < 500 && cause.code) {
    return errorsOnly({ form: [cause.message] });
  }
  return errorsOnly({ form: [SAVE_FAILED] });
}

/** 같은 키로 시작하는 칸(`skus.0.hsCode` → `skus`)인가 — 줄 단위 칸 오류를 받는 폼의 `isField` */
export const underAny =
  (...prefixes: string[]) =>
  (key: string) =>
    prefixes.some((prefix) => key === prefix || key.startsWith(`${prefix}.`));

/** 칸 오류 하나 더하기(같은 문구는 한 번) */
export function addError(errors: FormErrors, key: string, message: string) {
  errors[key] = [...new Set([...(errors[key] ?? []), message])];
}

/** 화면에서 내려 줄 저장 결과 — 섹션 화면은 상세로 옮기고, 옵션 · SKU 화면은 그 자리에서 이어서 고친다 */
export const SAVED_NOTICE = '저장했습니다.';

/** 목록 순서 바꾸기(이미지 · 옵션 · 맞춤 항목) — `offset` 은 -1(위로) · +1(아래로) */
export function moveSlot<T>(items: readonly T[], index: number, offset: -1 | 1): T[] {
  const target = index + offset;
  if (target < 0 || target >= items.length) return [...items];
  const next = [...items];
  [next[index], next[target]] = [next[target] as T, next[index] as T];
  return next;
}
