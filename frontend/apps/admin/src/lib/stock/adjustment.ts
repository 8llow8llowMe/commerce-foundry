import { ApiError } from '@cf/api-client';
import { accessDeniedOf, SAVE_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import type {
  AdjustmentReason,
  ProductForStock,
  StockAdjustmentRequest,
  StockAdmin,
} from './types';

/** SKU 의 옵션 조합 이름 — 옵션 그룹 순서대로 값 이름을 잇는다. 옵션 없는 상품은 '기본' */
export function skuLabel(product: ProductForStock, optionValueIds: string[]): string {
  if (optionValueIds.length === 0) return '기본';
  const names = new Map(
    product.optionGroups.flatMap((group) =>
      group.values.map((value) => [value.optionValueId, value.name] as const),
    ),
  );
  return optionValueIds.map((id) => names.get(id) ?? id).join(' / ');
}

/** 조정 폼 — 수량은 절대값(바꿀 결과 수량) 글자로 든다 */
export type AdjustForm = {
  stockManaged: boolean;
  quantity: string;
  reason: AdjustmentReason | '';
  memo: string;
};

export const MEMO_MAX = 200;
export const NOTHING_CHANGED = '바꾼 값이 없습니다. 수량이나 재고 관리 여부를 바꿔 주세요.';
export const QUANTITY_FORMAT_ERROR = '수량은 0 이상의 정수로 입력해 주세요.';

/** 무제한 SKU 는 숨은 옛 수량을 보이지 않도록 수량 칸을 비워 둔다(관리로 켜면 새로 넣는다) */
export function adjustForm(stock: StockAdmin): AdjustForm {
  return {
    stockManaged: stock.stockManaged,
    quantity: stock.stockManaged ? String(stock.availableQuantity) : '',
    reason: '',
    memo: '',
  };
}

export type BuildAdjustment =
  { ok: true; request: StockAdjustmentRequest } | { ok: false; errors: FormErrors };

/**
 * 조정 폼 → PATCH 본문. 바뀐 것만 보낸다.
 * 수량을 바꾸면 화면에서 본 판매 가능 수량을 `expectedQuantity` 로 함께 보낸다 — 그 사이 주문이 차감했으면 서버가 409 PRODUCT_020 으로 막는다(CAS).
 * 무제한(stockManaged false)으로 두면 수량은 보내지 않는다. 계산은 하지 않는다 — 입력한 결과 수량을 그대로 보낸다.
 */
export function adjustmentRequest(stock: StockAdmin, form: AdjustForm): BuildAdjustment {
  const managedChanged = form.stockManaged !== stock.stockManaged;
  let quantity: number | undefined;
  if (form.stockManaged) {
    const text = form.quantity.replace(/,/g, '').trim();
    if (!/^\d+$/.test(text) || !Number.isSafeInteger(Number(text))) {
      return { ok: false, errors: { quantity: [QUANTITY_FORMAT_ERROR] } };
    }
    // 무제한 → 관리로 바꿀 때는 지금 값이 의미가 없어 입력한 수량을 그대로 보낸다
    if (Number(text) !== stock.availableQuantity || managedChanged) quantity = Number(text);
  }
  if (!managedChanged && quantity === undefined)
    return { ok: false, errors: { form: [NOTHING_CHANGED] } };

  const request: StockAdjustmentRequest = {
    reason: form.reason === '' ? null : form.reason,
    memo: form.memo.trim() === '' ? null : form.memo.trim(),
  };
  if (managedChanged) request.stockManaged = form.stockManaged;
  if (quantity !== undefined) {
    request.quantity = quantity;
    request.expectedQuantity = stock.availableQuantity;
  }
  return { ok: true, request };
}

export const STOCK_CONFLICT = 'PRODUCT_020';
export const STOCK_CONFLICT_NOTICE =
  '그사이 주문 등으로 재고가 바뀌어 최신 수량을 다시 불러왔습니다. 확인한 뒤 다시 조정해 주세요.';
export const STOCK_UNKNOWN_NOTICE =
  '조정 결과를 확인하지 못해 최신 수량을 다시 불러왔습니다. 반영됐는지 확인해 주세요.';

const FORM_FIELDS = new Set(['quantity', 'stockManaged', 'reason', 'memo']);

export type AdjustFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'conflict' }
  | { kind: 'unknownOutcome' }
  | { kind: 'errors'; errors: FormErrors };

/**
 * 조정(PATCH) 실패를 화면 처리로 가른다. 409 PRODUCT_020 은 최신 수량을 다시 읽는다(덮어쓰지 않는다).
 * 칸을 특정하는 검증 오류는 그 칸에, 클래스 수준 검증(targetPresent · expectedQuantityPresent)과 그 밖은 폼 전체 오류다.
 */
export function adjustmentFailure(cause: unknown): AdjustFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.code === STOCK_CONFLICT) return { kind: 'conflict' };
  if (cause.kind === 'unknownOutcome') return { kind: 'unknownOutcome' };
  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const key = FORM_FIELDS.has(item.field) ? item.field : 'form';
      errors[key] = [...(errors[key] ?? []), item.message];
    }
    return { kind: 'errors', errors };
  }
  // 404 PRODUCT_002(없는 SKU) 등 — 서버 문구
  if (cause.status !== null && cause.status < 500 && cause.code) {
    return { kind: 'errors', errors: { form: [cause.message] } };
  }
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}
