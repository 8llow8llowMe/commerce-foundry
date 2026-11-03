import { moneyToDecimalString, type Currency, type Money } from '@cf/api-client';
import type { FormErrors } from '@/lib/api-errors';
import { amountOf, LIST_PRICE_NOT_HIGHER, PRICE_REQUIRED } from './create';
import type { AdminProductDetail } from './detail';
import { addError } from './section';

// 어드민 상품 가격 수정(#29 · BE #553) — `PUT /api/v1/admin/products/{productId}/pricing`.
// 기본 판매가 · 정가만 전체 교체한다(정가를 비우면 지운다). 통화는 상품 통화 그대로다 — 바꾸는 API 가 없다.
// 1회 최대 수량 · 프리오더를 고치는 API 는 아직 없다. 가격 규칙(정가 > 판매가, 판매중 SKU 중 추가금 0 하나)의 판정은 서버다(`PRODUCT_016`).

export type PricingForm = { salePrice: string; listPrice: string };

export type PricingRequest = { version: number; salePrice: Money; listPrice: Money | null };

export function pricingFormOf(product: AdminProductDetail): PricingForm {
  return {
    salePrice: moneyToDecimalString(product.salePrice),
    listPrice: product.listPrice ? moneyToDecimalString(product.listPrice) : '',
  };
}

export const samePricing = (a: PricingForm, b: PricingForm) =>
  a.salePrice.trim() === b.salePrice.trim() && a.listPrice.trim() === b.listPrice.trim();

/** 요청 검증 코드 → 문구 */
export const PRICING_VALIDATION: Record<string, string> = { PRODUCT_084: PRICE_REQUIRED };
export const PRICING_FIELDS = new Set(['salePrice', 'listPrice']);

export type BuildPricing =
  { ok: true; request: PricingRequest } | { ok: false; errors: FormErrors };

/** 폼 → PUT 본문. 금액은 글자를 최소 단위 정수로 바꾸기만 한다(계산하지 않는다) */
export function pricingRequest(
  form: PricingForm,
  currency: Currency,
  version: number,
): BuildPricing {
  const errors: FormErrors = {};
  const sale = amountOf(form.salePrice, currency);
  if (sale.ok === null) addError(errors, 'salePrice', PRICE_REQUIRED);
  else if (!sale.ok) addError(errors, 'salePrice', sale.message);

  const list = amountOf(form.listPrice, currency);
  if (list.ok === false) addError(errors, 'listPrice', list.message);
  else if (list.ok && sale.ok && list.amount <= sale.amount) {
    addError(errors, 'listPrice', LIST_PRICE_NOT_HIGHER);
  }

  if (!sale.ok || Object.keys(errors).length > 0) return { ok: false, errors };
  return {
    ok: true,
    request: {
      version,
      salePrice: { amount: sale.amount, currency },
      listPrice: list.ok ? { amount: list.amount, currency } : null,
    },
  };
}
