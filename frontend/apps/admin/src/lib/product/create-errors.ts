import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  fieldKey,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import {
  IMAGE_FORMAT_NOT_ALLOWED,
  IMAGE_SIZE_NOT_ALLOWED,
  UPLOAD_DISABLED,
} from '@/lib/upload/image-upload';
import {
  ALT_TEXT_INVALID,
  AMOUNT_FORMAT,
  AMOUNT_REQUIRED,
  AMOUNT_TOO_LARGE,
  CATEGORY_IDS_TOO_MANY,
  CATEGORY_INVALID,
  CATEGORY_REQUIRED,
  CHECK_FIELDS,
  CURRENCY_REQUIRED,
  DESCRIPTION_TOO_LONG,
  GROUP_NAME_INVALID,
  GROUP_VALUES_REQUIRED,
  GROUPS_TOO_MANY,
  HS_CODE_INVALID,
  IMAGES_MAX,
  INITIAL_STOCK_INVALID,
  MAX_QUANTITY_RANGE,
  NAME_CHARACTER,
  NAME_REQUIRED,
  NAME_TOO_LONG,
  QUANTITY_INVALID,
  SALE_STATUS_REQUIRED,
  SHIP_DATE_PAST,
  SHIP_DATE_WITHOUT_PREORDER,
  SKU_OPTION_INVALID,
  SKUS_SIZE,
  SUMMARY_INVALID,
  VALUE_INVALID,
  VISIBLE_REQUIRED,
  WEIGHT_INVALID,
} from './create';
import { CUSTOM_ORDER_VALIDATION } from './custom-order';

// 상품 등록(POST) 실패 → 화면 처리. 검증 문구는 서버 문구(칸 이름이 영문)를 그대로 보이지 않고 코드별 운영자 문구를 쓴다(원가 #395 와 같은 규칙).
// 코드 정본은 BE `ProductValidationMessage` · `ProductErrorCode`, 칸은 `fieldErrors.field` 경로(`skus[1].additionalPrice.amount`)로 찾는다.

export const CATEGORY_NOT_FOUND =
  '그사이 삭제된 카테고리가 있습니다. 카테고리를 다시 불러왔으니 다시 골라 주세요.';
export const OPTION_INVALID = '옵션 구성이 올바르지 않습니다. 옵션 이름과 값을 확인해 주세요.';
export const SKU_DUPLICATED = '같은 옵션 조합의 SKU 가 두 번 있습니다. 옵션 값을 확인해 주세요.';
export const PRICE_RULE = '가격 설정이 올바르지 않습니다. 정가 · 추가금 규칙을 확인해 주세요.';
export const BUSY = '다른 카테고리 변경을 처리 중입니다. 잠시 후 다시 등록해 주세요.';
/** 맞춤 제작 항목 정의 규칙 위반(`PRODUCT_011`) — 서버 문구에 이유가 붙는다 */
export const CUSTOM_ORDER_INVALID = '맞춤 제작 입력 항목을 확인해 주세요.';
export const IMAGES_INVALID = `이미지는 대표를 포함해 ${IMAGES_MAX}장까지, 대체 텍스트는 200자 이하로 확인해 주세요.`;
export const IMAGE_CONFIGURATION =
  '대표 이미지는 한 장이고 같은 이미지를 두 번 붙일 수 없습니다. 이미지를 확인해 주세요.';
/** 다른 운영자의 키 · 하루가 지나 지워진 업로드 · 잘못된 키(`PRODUCT_032`) — 서버는 어느 이유인지 알리지 않는다 */
export const IMAGE_UPLOAD_GONE =
  '올린 이미지를 찾을 수 없습니다. 이미지를 빼고 다시 올린 뒤 등록해 주세요(올린 지 하루가 지나면 지워집니다).';
/** 이미지 검증 · 복사는 저장(DB) 전에 한다 — 저장소 오류(`PRODUCT_035`)면 상품은 생기지 않았다 */
export const IMAGE_STORAGE_FAILED =
  '이미지 저장소에 연결하지 못해 등록하지 않았습니다. 잠시 후 다시 등록하거나 이미지를 빼고 등록해 주세요.';
/**
 * 결과를 모르는 실패(502 · 504 · 5xx · 연결 실패). 등록에는 멱등 키가 없어 다시 보내면 상품이 하나 더 생길 수 있다 —
 * 실패로 단정하지 않고 목록에서 확인하게 한다. 자동으로 다시 보내지 않는다.
 */
export const UNKNOWN_OUTCOME =
  '등록 결과를 확인하지 못했습니다. 상품이 이미 등록됐을 수 있으니 상품 목록에서 먼저 확인한 뒤, 없을 때만 다시 등록해 주세요.';

/** 요청 검증 코드 → 문구. 칸은 서버 경로로 찾는다(같은 코드를 여러 칸이 쓴다 — 금액 · 판매 상태) */
const VALIDATION: Record<string, string> = {
  PRODUCT_074: NAME_REQUIRED,
  PRODUCT_075: NAME_TOO_LONG,
  PRODUCT_076: NAME_CHARACTER,
  PRODUCT_077: SUMMARY_INVALID,
  PRODUCT_078: DESCRIPTION_TOO_LONG,
  PRODUCT_079: CATEGORY_REQUIRED,
  PRODUCT_080: CATEGORY_IDS_TOO_MANY,
  PRODUCT_081: CATEGORY_INVALID,
  PRODUCT_082: SALE_STATUS_REQUIRED,
  PRODUCT_083: VISIBLE_REQUIRED,
  PRODUCT_084: AMOUNT_REQUIRED,
  PRODUCT_085: AMOUNT_REQUIRED,
  PRODUCT_086: AMOUNT_FORMAT,
  PRODUCT_087: CURRENCY_REQUIRED,
  PRODUCT_088: MAX_QUANTITY_RANGE,
  PRODUCT_089: SHIP_DATE_WITHOUT_PREORDER,
  PRODUCT_090: GROUPS_TOO_MANY,
  PRODUCT_091: OPTION_INVALID,
  PRODUCT_092: GROUP_NAME_INVALID,
  PRODUCT_093: GROUP_VALUES_REQUIRED,
  PRODUCT_094: VALUE_INVALID,
  PRODUCT_095: SKUS_SIZE,
  PRODUCT_096: SKUS_SIZE,
  PRODUCT_097: SKU_OPTION_INVALID,
  PRODUCT_098: WEIGHT_INVALID,
  PRODUCT_099: HS_CODE_INVALID,
  PRODUCT_169: INITIAL_STOCK_INVALID,
  PRODUCT_037: AMOUNT_TOO_LARGE,
  PRODUCT_038: SHIP_DATE_PAST,
  PRODUCT_137: CATEGORY_INVALID,
  PRODUCT_106: QUANTITY_INVALID,
  PRODUCT_019: IMAGES_INVALID,
  PRODUCT_100: CHECK_FIELDS,
  ...CUSTOM_ORDER_VALIDATION,
};

/** 화면에 칸이 있는 키. 그 밖의 경로는 폼 전체(`form`)로 — 오류를 그릴 곳이 없어 사라지지 않게 */
const TOP_LEVEL = new Set([
  'name',
  'summary',
  'descriptionHtml',
  'categoryId',
  'categoryIds',
  'saleStatus',
  'visible',
  'salePrice',
  'listPrice',
  'maxQuantityPerOrder',
  'expectedShipDate',
  'optionGroups',
  'skus',
  'images',
]);
const SKU_FIELDS = new Set(['additionalPrice', 'saleStatus', 'weightGrams', 'hsCode']);
const CUSTOM_ORDER_TOP = new Set(['enabled', 'notice', 'leadTimeDays', 'fields']);
const CUSTOM_FIELD_FIELDS = new Set([
  'fieldKey',
  'label',
  'inputType',
  'unit',
  'minValue',
  'maxValue',
  'stepValue',
  'helpText',
]);

/**
 * 서버 `fieldErrors.field` → 폼 칸 키. 공통 `fieldKey` 로 점 경로를 만든 뒤 이 폼에 맞춘다.
 * - `@AssertTrue` 메서드 이름(`expectedShipDateWithPreorder` · `skus[0].hsCodeValid`)은 그 칸으로
 * - 처음 재고(`skus[0].initialStock.quantity`)는 처음 재고 칸, SKU 옵션 조합(`skus[0].optionValues[1]`)은 SKU 표 전체로
 * - 옵션 그룹 이름 · 값은 그 그룹(`optionGroups.0.name` · `optionGroups.0.values`)
 * - 이미지 대체 텍스트는 그 칸(`images.0.altText`, 본문 번호 — `imageEntries`), 그 밖의 이미지 오류는 이미지 칸 전체(`images`)
 * - 맞춤 제작은 그 칸(`customOrder.notice` · `customOrder.fields.0.label`), 모르는 항목 칸은 항목 전체(`customOrder.fields`)
 */
export function createFieldKey(path: string): string {
  const key = fieldKey(path);
  if (key === 'expectedShipDateWithPreorder') return 'expectedShipDate';
  if (TOP_LEVEL.has(key)) return key;
  const parts = key.split('.');
  const [head, index, field] = parts;
  if (head === 'skus' && index !== undefined && /^\d+$/.test(index)) {
    if (field === 'hsCodeValid') return `skus.${index}.hsCode`;
    if (field === 'initialStock') return `skus.${index}.initialStock`;
    if (field !== undefined && SKU_FIELDS.has(field)) return `skus.${index}.${field}`;
    return 'skus';
  }
  if (head === 'images') {
    if (index !== undefined && /^\d+$/.test(index) && field === 'altText') {
      return `images.${index}.altText`;
    }
    return 'images';
  }
  if (head === 'customOrder') {
    const [, top, row, field] = parts;
    if (top === 'fields' && row !== undefined && /^\d+$/.test(row)) {
      if (field !== undefined && CUSTOM_FIELD_FIELDS.has(field)) {
        return `customOrder.fields.${row}.${field}`;
      }
      return 'customOrder.fields';
    }
    if (top !== undefined && CUSTOM_ORDER_TOP.has(top)) return `customOrder.${top}`;
    return 'customOrder.fields';
  }
  if (head === 'optionGroups' && index !== undefined && /^\d+$/.test(index)) {
    if (field === 'name' || field === 'values') return `optionGroups.${index}.${field}`;
    return 'optionGroups';
  }
  return 'form';
}

export type CreateFailure =
  | { kind: 'access'; denied: AccessDenied }
  | {
      kind: 'errors';
      errors: FormErrors;
      /** 카테고리 트리를 다시 읽는다(`PRODUCT_012`) */
      reloadCategories: boolean;
      /** 등록됐을 수 있다 — 목록 확인을 안내한다 */
      uncertain: boolean;
    };

const errorsOnly = (errors: FormErrors, reloadCategories = false): CreateFailure => ({
  kind: 'errors',
  errors,
  reloadCategories,
  uncertain: false,
});

/** 서버 업무 문구(한국어 사유가 붙는다 — "옵션 구성이 올바르지 않습니다: 옵션 이름이 겹칩니다(사이즈)")가 있으면 그것 */
const serverMessage = (cause: ApiError, fallback: string) =>
  cause.message && cause.message.trim() !== '' ? cause.message : fallback;

/**
 * 등록 실패를 화면 처리로 가른다. 폼 입력은 언제나 지킨다.
 * - 401 은 폼을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`), 403 은 접근 상태다.
 * - 결과를 모르는 실패는 `uncertain` — 멱등 키가 없는 쓰기라 다시 보내면 상품이 둘이 된다.
 * - 429 · 그 밖의 4xx 는 처리되지 않은 요청이다.
 */
export function createFailure(cause: unknown): CreateFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return errorsOnly({ form: [SESSION_EXPIRED_ON_SAVE] });
  if (denied) return { kind: 'access', denied };
  // 이미지 첨부 오류는 저장(DB) 전에 난다 — 503(035 · 036)이어도 등록되지 않았다
  const image = cause instanceof ApiError ? imageFailure(cause.code) : null;
  if (image) return errorsOnly(image);
  if (!(cause instanceof ApiError) || isUncertain(cause)) {
    return {
      kind: 'errors',
      errors: { form: [UNKNOWN_OUTCOME] },
      reloadCategories: false,
      uncertain: true,
    };
  }

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const key = createFieldKey(item.field);
      const message = key.endsWith('.altText')
        ? ALT_TEXT_INVALID
        : (VALIDATION[item.code] ?? CHECK_FIELDS);
      errors[key] = [...new Set([...(errors[key] ?? []), message])];
    }
    return errorsOnly(errors);
  }

  switch (cause.code) {
    case 'PRODUCT_012':
      // 없는 카테고리 아이디는 서버 문구에 원문으로 들어 있다 — 보이지 않는다(#305)
      return errorsOnly({ categoryId: [CATEGORY_NOT_FOUND] }, true);
    case 'PRODUCT_013':
      return errorsOnly({ optionGroups: [serverMessage(cause, OPTION_INVALID)] });
    case 'PRODUCT_014':
      return errorsOnly({ skus: [serverMessage(cause, SKU_DUPLICATED)] });
    case 'PRODUCT_011':
      return errorsOnly({ 'customOrder.fields': [serverMessage(cause, CUSTOM_ORDER_INVALID)] });
    case 'PRODUCT_016':
      return errorsOnly({ form: [serverMessage(cause, PRICE_RULE)] });
    case 'PRODUCT_045':
      return errorsOnly({ form: [BUSY] });
    default:
      break;
  }
  // 칸을 모르는 검증 오류(깨진 본문 PRODUCT_100 · 금액 상한 등)
  const known = cause.code ? VALIDATION[cause.code] : undefined;
  if (known) return errorsOnly({ form: [known] });
  if (cause.kind === 'rateLimited') return errorsOnly({ form: [cause.message] });
  if (cause.status !== null && cause.status < 500 && cause.code) {
    return errorsOnly({ form: [cause.message] });
  }
  return errorsOnly({ form: [SAVE_FAILED] });
}

/** 이미지 첨부(BE #9 F2) 업무 오류 → 칸 오류. 아니면 null */
function imageFailure(code: string | null): FormErrors | null {
  switch (code) {
    case 'PRODUCT_018':
      return { images: [IMAGE_CONFIGURATION] };
    case 'PRODUCT_032':
      return { images: [IMAGE_UPLOAD_GONE] };
    case 'PRODUCT_033':
      return { images: [IMAGE_FORMAT_NOT_ALLOWED] };
    case 'PRODUCT_034':
      return { images: [IMAGE_SIZE_NOT_ALLOWED] };
    case 'PRODUCT_035':
      return { form: [IMAGE_STORAGE_FAILED] };
    case 'PRODUCT_036':
      return { form: [UPLOAD_DISABLED] };
    default:
      return null;
  }
}

/** 서버가 처리했는지 모르는 실패 — 연결 실패 · 봉투 아님 · 502/504 · 503 · 5xx */
function isUncertain(cause: ApiError): boolean {
  if (
    cause.kind === 'network' ||
    cause.kind === 'invalidResponse' ||
    cause.kind === 'unknownOutcome' ||
    cause.kind === 'unavailable'
  ) {
    return true;
  }
  return cause.status !== null && cause.status >= 500;
}

/** 요약에 보일 칸 이름 — 오류 키의 첫 마디(`skus.0.hsCode` → `skus`)로 찾는다 */
const FIELD_LABELS: Record<string, string> = {
  name: '상품명',
  summary: '요약',
  descriptionHtml: '상세 설명',
  images: '이미지',
  thumbnail: '대표 이미지',
  gallery: '추가 이미지',
  categoryId: '카테고리',
  categoryIds: '카테고리',
  saleStatus: '판매 상태',
  visible: '스토어 노출',
  currency: '통화',
  salePrice: '판매가',
  listPrice: '정가',
  maxQuantityPerOrder: '1회 최대 수량',
  preorder: '프리오더',
  expectedShipDate: '출고 예정일',
  optionGroups: '옵션',
  skus: 'SKU',
  customOrder: '맞춤 제작',
};

/**
 * 폼 위 요약 — 폼 전체 문구와, 칸 오류가 있으면 "입력값을 확인해 주세요." 아래에 **어느 칸이 왜** 틀렸는지 한 줄씩(#29 시연).
 * 같은 칸 이름은 한 줄로 모은다(옵션 1 · 옵션 2 → 옵션). 이름을 모르는 칸은 "그 밖의 칸 n곳" 으로 센다. 칸 옆에도 같은 문구가 있다.
 */
export function errorSummary(errors: FormErrors): string[] {
  const summary = [...(errors.form ?? [])];
  const lines = new Map<string, string>();
  let unknown = 0;
  for (const [key, messages] of Object.entries(errors)) {
    if (key === 'form' || messages.length === 0) continue;
    const label = FIELD_LABELS[key.split('.')[0] ?? ''];
    if (!label) unknown += 1;
    else if (!lines.has(label)) lines.set(label, `${label} — ${messages[0]}`);
  }
  if (lines.size === 0 && unknown === 0) return summary;
  summary.push(CHECK_FIELDS, ...lines.values());
  if (unknown > 0) summary.push(`그 밖의 칸 ${unknown}곳`);
  return summary;
}
