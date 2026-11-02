import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  fieldKey,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type FormErrors,
} from '@/lib/api-errors';
import {
  CHECK_FIELDS,
  createdProductHref,
  DESCRIPTION_MAX,
  DESCRIPTION_TOO_LONG,
  isPlainText,
  NAME_CHARACTER,
  NAME_MAX,
  NAME_REQUIRED,
  NAME_TOO_LONG,
  SALE_STATUS_REQUIRED,
  SUMMARY_INVALID,
  SUMMARY_MAX,
  VISIBLE_REQUIRED,
  type ProductCreateResponse,
  type SaleStatusCode,
} from './create';
import { adminProductPath, type AdminProductDetail } from './detail';

// 어드민 상품 기본 정보 수정(#29 · BE #9 후속) — `PUT /api/v1/admin/products/{productId}/header`.
// 계약 정본은 `backend/docs/api-design-guide.md` "어드민 상품 기본 정보 수정 API", 칸 규칙은 BE `ProductHeaderUpdateRequest`.
// 상품명 · 요약 · 상세 HTML · 판매 상태 · 노출만 전체 교체한다. 카테고리 · 가격 · 프리오더 · 옵션 · SKU · 이미지는 바꾸지 않는다(BE 후속).
// 상세 조회의 `version` 을 되돌려 보내는 낙관적 락이다 — 그사이 다른 수정 · 삭제가 먼저 반영되면 409 `PRODUCT_010`.

export const productHeaderPath = (productId: string) => `${adminProductPath(productId)}/header`;
export const productEditHref = (productId: string) => `/products/${productId}/edit`;

export type ProductHeaderForm = {
  name: string;
  summary: string;
  descriptionHtml: string;
  saleStatus: SaleStatusCode;
  visible: boolean;
};

/** BE `ProductHeaderUpdateRequest`. `summary` · `descriptionHtml` 은 null 로 지운다 */
export type ProductHeaderUpdateRequest = {
  version: number;
  name: string;
  summary: string | null;
  descriptionHtml: string | null;
  saleStatus: SaleStatusCode;
  visible: boolean;
};

/** 200 `ProductHeaderUpdateAdminResponse` — 등록 응답과 모양이 같다(저장된 상세 + 정제 여부) */
export type ProductHeaderUpdateResponse = ProductCreateResponse;

/** 저장 뒤 상세로 — 상세 설명을 정리해 저장했으면 상세가 한 번 알린다(등록과 같은 표시) */
export const updatedProductHref = (response: ProductHeaderUpdateResponse) =>
  createdProductHref(response);

/** 서버 판매 상태 코드. 모르는 값이면 판매 종료로 둔다(고객에게 팔리지 않는 쪽) */
const saleStatusOf = (code: string): SaleStatusCode =>
  code === 'ON_SALE' ? 'ON_SALE' : 'STOP_SELLING';

/** 상세 → 폼. 상세 설명은 서버가 저장한 원문 그대로다(textarea 에만 넣고 렌더링하지 않는다) */
export function headerFormOf(product: AdminProductDetail): ProductHeaderForm {
  return {
    name: product.name,
    summary: product.summary ?? '',
    descriptionHtml: product.descriptionHtml ?? '',
    saleStatus: saleStatusOf(product.saleStatus.code),
    visible: product.visible,
  };
}

export type BuildHeader =
  { ok: true; request: ProductHeaderUpdateRequest } | { ok: false; errors: FormErrors };

/** 폼 → PUT 본문. 등록과 같은 칸 규칙이다(문자 검사는 앞뒤 공백을 뺀 값으로) */
export function headerRequest(form: ProductHeaderForm, version: number): BuildHeader {
  const errors: FormErrors = {};
  const name = form.name.trim();
  if (name === '') errors.name = [NAME_REQUIRED];
  else if (!isPlainText(name)) errors.name = [NAME_CHARACTER];
  else if (name.length > NAME_MAX) errors.name = [NAME_TOO_LONG];

  const summary = form.summary.trim();
  if (!isPlainText(summary) || summary.length > SUMMARY_MAX) errors.summary = [SUMMARY_INVALID];
  if (form.descriptionHtml.length > DESCRIPTION_MAX)
    errors.descriptionHtml = [DESCRIPTION_TOO_LONG];

  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return {
    ok: true,
    request: {
      version,
      name,
      summary: summary === '' ? null : summary,
      descriptionHtml: form.descriptionHtml.trim() === '' ? null : form.descriptionHtml,
      saleStatus: form.saleStatus,
      visible: form.visible,
    },
  };
}

/** 폼 그대로 다시 보내도 같은 결과인가 — 바뀐 것이 없으면 저장을 막는다 */
export function sameHeader(a: ProductHeaderForm, b: ProductHeaderForm): boolean {
  return (
    a.name === b.name &&
    a.summary === b.summary &&
    a.descriptionHtml === b.descriptionHtml &&
    a.saleStatus === b.saleStatus &&
    a.visible === b.visible
  );
}

export const PRODUCT_MODIFICATION_CONFLICT = 'PRODUCT_010';
const PRODUCT_NOT_FOUND = 'PRODUCT_001';

export const UPDATE_CONFLICT =
  '그사이 다른 곳에서 이 상품이 바뀌었습니다. 최신 내용을 불러온 뒤 다시 수정해 주세요(입력한 내용은 최신 내용으로 바뀝니다).';
/** 502 · 504 · 5xx · 연결 실패 — 저장됐을 수 있다. 같은 버전으로 다시 보내면 저장됐을 때 409 라 덮어쓰지는 않는다 */
export const UPDATE_UNKNOWN =
  '저장 결과를 확인하지 못했습니다. 최신 내용을 불러와 저장됐는지 확인해 주세요.';
/** 상세의 `version` 이 비었다(이관 · 시드 데이터) — 서버가 버전 없는 수정을 받지 않는다 */
export const VERSION_MISSING =
  '이 상품은 수정 기준 버전이 없어 이 화면에서 수정할 수 없습니다. 개발 담당자에게 알려 주세요.';

/** 요청 검증 코드 → 문구(등록과 같은 코드를 쓴다) */
const VALIDATION: Record<string, string> = {
  PRODUCT_074: NAME_REQUIRED,
  PRODUCT_075: NAME_TOO_LONG,
  PRODUCT_076: NAME_CHARACTER,
  PRODUCT_077: SUMMARY_INVALID,
  PRODUCT_078: DESCRIPTION_TOO_LONG,
  PRODUCT_082: SALE_STATUS_REQUIRED,
  PRODUCT_083: VISIBLE_REQUIRED,
};
const FIELDS = new Set(['name', 'summary', 'descriptionHtml', 'saleStatus', 'visible']);

export type UpdateFailure =
  /** 403 — 화면 대신 `AccessNotice` */
  | { kind: 'forbidden' }
  /** 없거나 삭제된 상품 — 목록으로 */
  | { kind: 'gone' }
  /** 폼을 지키고 문구를 보인다. `reload` 면 "최신 내용 불러오기" 를 함께 낸다(충돌 · 결과 모름) */
  | { kind: 'errors'; errors: FormErrors; reload: boolean };

const errorsOnly = (errors: FormErrors, reload = false): UpdateFailure => ({
  kind: 'errors',
  errors,
  reload,
});

/** 수정 실패 → 화면 처리. 폼 입력은 언제나 지킨다. 자동으로 다시 보내지 않는다 */
export function updateFailure(cause: unknown): UpdateFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return errorsOnly({ form: [SESSION_EXPIRED_ON_SAVE] });
  if (denied) return { kind: 'forbidden' };
  if (!(cause instanceof ApiError)) return errorsOnly({ form: [UPDATE_UNKNOWN] }, true);

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const path = fieldKey(item.field);
      const key = FIELDS.has(path) ? path : 'form';
      const message = VALIDATION[item.code] ?? CHECK_FIELDS;
      errors[key] = [...new Set([...(errors[key] ?? []), message])];
    }
    return errorsOnly(errors);
  }
  if (cause.code === PRODUCT_NOT_FOUND) return { kind: 'gone' };
  if (cause.code === PRODUCT_MODIFICATION_CONFLICT)
    return errorsOnly({ form: [UPDATE_CONFLICT] }, true);
  const known = cause.code ? VALIDATION[cause.code] : undefined;
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
