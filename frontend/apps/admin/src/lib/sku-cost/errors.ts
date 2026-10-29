import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  LOAD_FAILED,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import {
  AMOUNT_NEGATIVE,
  AMOUNT_REQUIRED,
  REASON_CHARACTER,
  REASON_REQUIRED,
  REASON_TOO_LONG,
  START_IN_FUTURE,
  START_TOO_EARLY,
} from './form';

// 원가 쓰기 · 읽기 실패 → 화면 처리. 운영자에게는 서버 문구(칸 이름이 영문으로 들어 있다)를 그대로 보이지 않고
// 코드별 업무 문구를 쓴다(메인 전시 #305 와 같은 규칙). 코드 정본은 BE `SkuCostErrorCode` · `SkuCostValidationMessage`.

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const SKU_NOT_FOUND = 'SKU 를 찾을 수 없습니다. 상품 화면을 새로 고쳐 확인해 주세요.';
export const CURRENCY_LOCKED =
  '이 원가 유형은 처음 입력한 통화로만 기록할 수 있습니다. 원가를 다시 불러왔으니 통화를 확인해 주세요.';
export const NOT_AFTER_LATEST =
  '그사이 더 늦은 적용 시작의 원가가 추가되었습니다. 이력을 다시 불러왔으니 확인한 뒤 다시 입력해 주세요.';
export const RETROACTIVE =
  '이미 원가가 있는 유형은 지금부터만 적용할 수 있습니다. 지난 구간의 원가는 바꾸지 않습니다.';
export const DUPLICATED =
  '같은 적용 시작 시각의 원가가 먼저 저장되었습니다. 이력을 다시 불러왔으니 확인해 주세요.';
export const BUSY = '다른 원가 저장을 처리 중입니다. 잠시 후 다시 시도해 주세요.';
export const UNKNOWN_OUTCOME =
  '저장 결과를 확인하지 못해 원가와 이력을 다시 불러왔습니다. 추가됐는지 확인한 뒤 필요할 때만 다시 저장해 주세요.';

/** 요청 검증 코드 → [폼 칸, 문구]. 칸 키는 폼(`CostForm`)의 칸, 폼 전체는 `form` */
const VALIDATION: Record<string, [string, string]> = {
  SKU_COST_101: ['costType', '원가 유형을 고르세요.'],
  SKU_COST_102: ['amount', AMOUNT_REQUIRED],
  SKU_COST_103: ['amount', AMOUNT_REQUIRED],
  SKU_COST_104: ['amount', AMOUNT_NEGATIVE],
  SKU_COST_105: ['currency', '통화를 고르세요.'],
  SKU_COST_106: ['reason', REASON_REQUIRED],
  SKU_COST_107: ['reason', REASON_TOO_LONG],
  SKU_COST_108: ['reason', REASON_CHARACTER],
  SKU_COST_109: ['effectiveFrom', START_TOO_EARLY],
  SKU_COST_110: ['form', SKU_NOT_FOUND],
  PRODUCT_100: ['form', CHECK_FIELDS],
};

/** 업무 오류 코드 → [폼 칸, 문구, 원가 · 이력을 다시 읽을지]. 다시 읽는 것은 그사이 타임라인이 바뀌었다는 뜻이라서다 */
const BUSINESS: Record<string, [string, string, boolean]> = {
  SKU_COST_001: ['effectiveFrom', START_IN_FUTURE, false],
  SKU_COST_002: ['effectiveFrom', NOT_AFTER_LATEST, true],
  SKU_COST_003: ['currency', CURRENCY_LOCKED, true],
  SKU_COST_004: ['form', DUPLICATED, true],
  SKU_COST_005: ['form', BUSY, false],
  SKU_COST_006: ['effectiveFrom', RETROACTIVE, true],
  SKU_COST_110: ['form', SKU_NOT_FOUND, false],
  PRODUCT_002: ['form', SKU_NOT_FOUND, false],
};

/** 서버 칸 경로 → 폼 칸 키(코드를 모를 때). `amount.currency` 는 통화 칸, 그 밖의 금액 경로는 금액 칸이다 */
function formKey(path: string): string {
  if (path === 'amount.currency') return 'currency';
  if (path === 'amount' || path === 'amount.amount') return 'amount';
  if (path === 'effectiveFromInRange' || path === 'effectiveFrom') return 'effectiveFrom';
  if (path === 'costType' || path === 'reason') return path;
  return 'form';
}

export type CostFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'errors'; errors: FormErrors; reload: boolean };

/**
 * 원가 추가(POST) 실패를 화면 처리로 가른다. 폼 입력은 지킨다 — 다시 읽어도 폼을 비우지 않는다.
 * 401 은 폼을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`). 502 · 504 는 멱등 키가 없는 쓰기라 실패로 단정하지 않고
 * 다시 읽어 확인하게 한다 — "지금부터" 로 다시 보내면 행이 하나 더 생긴다.
 */
export function costFailure(cause: unknown): CostFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] }, reload: false };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError))
    return { kind: 'errors', errors: { form: [SAVE_FAILED] }, reload: false };
  if (cause.kind === 'unknownOutcome')
    return { kind: 'errors', errors: { form: [UNKNOWN_OUTCOME] }, reload: true };

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const [key, message] = VALIDATION[item.code] ?? [formKey(item.field), CHECK_FIELDS];
      errors[key] = [...new Set([...(errors[key] ?? []), message])];
    }
    return { kind: 'errors', errors, reload: false };
  }
  const business = cause.code ? BUSINESS[cause.code] : undefined;
  if (business) {
    const [key, message, reload] = business;
    return { kind: 'errors', errors: { [key]: [message] }, reload };
  }
  // 깨진 본문(PRODUCT_100) 등 칸을 모르는 검증 오류
  if (cause.code && VALIDATION[cause.code]) {
    const [key, message] = VALIDATION[cause.code]!;
    return { kind: 'errors', errors: { [key]: [message] }, reload: false };
  }
  if (cause.status !== null && cause.status < 500 && cause.code) {
    return { kind: 'errors', errors: { form: [cause.message] }, reload: false };
  }
  return { kind: 'errors', errors: { form: [SAVE_FAILED] }, reload: false };
}

export type ReadFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'failed'; message: string };

/** 현재 원가 · 이력 읽기 실패. 없는 SKU(404 `PRODUCT_002`) · 잘못된 SKU 아이디(`SKU_COST_110`)는 "SKU 없음" 이다 */
export function readFailure(cause: unknown): ReadFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (
    cause instanceof ApiError &&
    (cause.code === 'PRODUCT_002' || cause.code === 'SKU_COST_110' || cause.kind === 'notFound')
  ) {
    return { kind: 'notFound' };
  }
  return { kind: 'failed', message: LOAD_FAILED };
}
