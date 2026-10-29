import { parseMoneyInput, type Currency } from '@cf/api-client';
import type { FormErrors } from '@/lib/api-errors';
import { localInputToIso } from '@/lib/display/text';
import {
  COST_TYPES,
  FUTURE_TOLERANCE_MS,
  REASON_MAX,
  type CostTypeCode,
  type SkuCostCreateRequest,
  type SkuCostItem,
  type SkuCurrentCosts,
} from './types';

// 원가 추가 폼 → POST 본문. 입력 규칙은 BE 요청 검증 · 시간 규칙과 같다(`backend/docs/api-design-guide.md` §2-12).
// 정본은 서버다. 화면은 같은 규칙으로 미리 막아 저장 버튼을 누른 뒤에야 알게 되는 일을 줄인다. 금액은 계산하지 않고 표기만 바꾼다.

export const CURRENCIES: readonly Currency[] = ['KRW', 'USD'];

/** 폼 칸은 글자 그대로 둔다. 시작은 datetime-local 글자(이 컴퓨터 시간대) */
export type CostForm = {
  costType: CostTypeCode;
  amount: string;
  currency: Currency;
  /** now — 지금부터(effectiveFrom 을 비워 보낸다), at — 시각 지정(그 유형의 첫 원가일 때만) */
  start: 'now' | 'at';
  effectiveFrom: string;
  reason: string;
};

export const AMOUNT_REQUIRED = '금액을 입력해 주세요. 0원이면 0 을 입력합니다.';
export const AMOUNT_FORMAT = '금액 형식이 올바르지 않습니다.';
export const AMOUNT_NEGATIVE = '금액은 0 이상이어야 합니다.';
export const REASON_REQUIRED = '변경 사유를 입력해 주세요.';
export const REASON_TOO_LONG = `변경 사유는 ${REASON_MAX}자 이하로 입력해 주세요.`;
export const REASON_CHARACTER = '변경 사유에는 줄바꿈 · 탭 · 보이지 않는 문자를 쓸 수 없습니다.';
export const START_FORMAT = '적용 시작 시각을 입력해 주세요.';
export const START_TOO_EARLY = '적용 시작은 1970-01-01 09:00(한국 시간) 이후여야 합니다.';
export const START_IN_FUTURE = '적용 시작은 지금부터 5분 뒤까지만 지정할 수 있습니다.';

/** 지금 적용되는 그 유형의 원가. 없으면(미입력) null — 0원과 다르다 */
export function currentCostOf(
  current: SkuCurrentCosts,
  costType: CostTypeCode,
): SkuCostItem | null {
  return current.costs.find((item) => item.costType.code === costType) ?? null;
}

/** 현재 원가를 유형 순서(GOODS → PACKAGING)대로. 미입력 유형은 item 이 null 이다 */
export function currentCostRows(
  current: SkuCurrentCosts,
): { costType: CostTypeCode; item: SkuCostItem | null }[] {
  return COST_TYPES.map((costType) => ({ costType, item: currentCostOf(current, costType) }));
}

/**
 * 그 유형에 원가가 이미 있으면 통화는 첫 행 통화로 고정이고(`SKU_COST_003`) 시작은 지금 이후만 된다(`SKU_COST_006`).
 * 현재 원가의 통화가 곧 타임라인 통화다. 미입력이면 null — 통화 · 과거 시작을 고를 수 있다.
 */
export function lockedCurrency(current: SkuCurrentCosts, costType: CostTypeCode): Currency | null {
  return currentCostOf(current, costType)?.amount.currency ?? null;
}

export function costForm(current: SkuCurrentCosts, costType: CostTypeCode = 'GOODS'): CostForm {
  return {
    costType,
    amount: '',
    currency: lockedCurrency(current, costType) ?? 'KRW',
    start: 'now',
    effectiveFrom: '',
    reason: '',
  };
}

/** 유형을 바꾸면 그 유형의 고정 통화로 맞추고, 원가가 있는 유형이면 시작을 "지금부터" 로 되돌린다 */
export function withCostType(
  form: CostForm,
  current: SkuCurrentCosts,
  costType: CostTypeCode,
): CostForm {
  const locked = lockedCurrency(current, costType);
  return {
    ...form,
    costType,
    currency: locked ?? form.currency,
    start: locked ? 'now' : form.start,
  };
}

// BE `SkuCostReasonValidator` — 공백 · 서식 문자만이면 필수, 제어(\p{Cc}) · 서식(\p{Cf}) 문자는 어디에 있든 거절
const REASON_BLANK = /^[\s\p{Zs}\p{Cf}]*$/u;
const REASON_REJECTED = /[\p{Cc}\p{Cf}]/u;

export type BuildCost =
  { ok: true; request: SkuCostCreateRequest } | { ok: false; errors: FormErrors };

/**
 * 폼 → POST 본문. 원가가 있는 유형은 통화를 고정 통화로, 시작을 "지금부터" 로 보낸다(화면이 고를 수 없게 막는 것과 같다).
 * `now` 는 미래 한도(지금 + 5분)를 미리 보는 데만 쓴다 — 서버 시각과 어긋날 수 있어 최종 판정은 서버다.
 */
export function costRequest(
  form: CostForm,
  current: SkuCurrentCosts,
  now: number = Date.now(),
): BuildCost {
  const errors: FormErrors = {};
  const locked = lockedCurrency(current, form.costType);
  const currency = locked ?? form.currency;

  const amount = parseMoneyInput(form.amount, currency);
  if (amount === null) errors.amount = [AMOUNT_REQUIRED];
  else if (Number.isNaN(amount)) errors.amount = [AMOUNT_FORMAT];

  let effectiveFrom: string | undefined;
  if (!locked && form.start === 'at') {
    const iso = localInputToIso(form.effectiveFrom);
    if (iso === null) errors.effectiveFrom = [START_FORMAT];
    else if (Date.parse(iso) < 0) errors.effectiveFrom = [START_TOO_EARLY];
    else if (Date.parse(iso) > now + FUTURE_TOLERANCE_MS) errors.effectiveFrom = [START_IN_FUTURE];
    else effectiveFrom = iso;
  }

  const reason = form.reason.trim();
  if (REASON_BLANK.test(form.reason)) errors.reason = [REASON_REQUIRED];
  else if (REASON_REJECTED.test(form.reason)) errors.reason = [REASON_CHARACTER];
  else if ([...reason].length > REASON_MAX) errors.reason = [REASON_TOO_LONG];

  if (Object.keys(errors).length > 0 || amount === null || Number.isNaN(amount)) {
    return { ok: false, errors };
  }
  const request: SkuCostCreateRequest = {
    costType: form.costType,
    amount: { amount, currency },
    reason,
  };
  if (effectiveFrom !== undefined) request.effectiveFrom = effectiveFrom;
  return { ok: true, request };
}
