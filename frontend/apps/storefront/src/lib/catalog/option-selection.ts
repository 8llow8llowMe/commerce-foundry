import type { Money } from '@cf/api-client';
import type { ProductDetail, ProductOptionGroup, ProductSku } from './types';

/** 옵션 그룹 id → 고른 옵션값 id */
export type OptionSelection = Record<string, string>;

/** 화면에서 옵션값 버튼의 상태 */
export type OptionValueState = 'available' | 'soldOut' | 'unavailable';

const selectedValueIds = (selection: OptionSelection) => Object.values(selection);

function skusMatching(skus: ProductSku[], valueIds: string[]): ProductSku[] {
  return skus.filter((sku) => valueIds.every((id) => sku.optionValueIds.includes(id)));
}

/** 모든 그룹을 골랐을 때만 SKU 를 찾는다. 옵션 없는 상품은 SKU 하나가 바로 고른 것이다 */
export function resolveSku(
  groups: ProductOptionGroup[],
  skus: ProductSku[],
  selection: OptionSelection,
): ProductSku | null {
  if (groups.some((group) => !selection[group.optionGroupId])) return null;
  const ids = selectedValueIds(selection);
  return (
    skus.find(
      (sku) => sku.optionValueIds.length === ids.length && skusMatching([sku], ids).length === 1,
    ) ?? null
  );
}

/**
 * 이 값을 고르면 살 수 있는가. 다른 그룹에서 이미 고른 값과 함께 맞는 SKU 들을 본다.
 * - 판매중 SKU 가 하나라도 있으면 available
 * - 맞는 SKU 가 모두 품절이면 soldOut
 * - 맞는 SKU 가 없거나 판매 종료뿐이면 unavailable
 */
export function optionValueState(
  skus: ProductSku[],
  selection: OptionSelection,
  groupId: string,
  valueId: string,
): OptionValueState {
  const others = Object.entries(selection)
    .filter(([id]) => id !== groupId)
    .map(([, value]) => value);
  const matching = skusMatching(skus, [...others, valueId]);
  if (matching.some((sku) => sku.saleStatus.code === 'ON_SALE')) return 'available';
  if (matching.some((sku) => sku.saleStatus.code === 'SOLD_OUT')) return 'soldOut';
  return 'unavailable';
}

export type DisplayPrice = {
  salePrice: Money;
  /** 할인 전 정가 — 상품 표시가에 대한 값이라, SKU 를 골랐으면 그 판매가가 표시가와 같을 때만 */
  listPrice: Money | null;
  discountRate: number | null;
  /** SKU 를 아직 정하지 않았고 옵션에 따라 가격이 올라간다 */
  from: boolean;
};

/**
 * 표시할 가격. SKU 가 정해지면 그 SKU 의 서버 판매가, 아니면 상품 최저가다.
 * 클라이언트는 더하거나 빼지 않는다 — 금액은 모두 서버 값이다.
 */
export function displayPrice(
  product: Pick<ProductDetail, 'salePrice' | 'listPrice' | 'discountRate' | 'skus'>,
  sku: ProductSku | null,
): DisplayPrice {
  if (sku) {
    // 정가 · 할인율은 상품의 표시 판매가(최저가)에 대한 값이다. 판매가가 다른 SKU 에 붙이면 틀린 정가가 된다
    const base =
      sku.salePrice.amount === product.salePrice.amount &&
      sku.salePrice.currency === product.salePrice.currency;
    return {
      salePrice: sku.salePrice,
      listPrice: base ? product.listPrice : null,
      discountRate: base ? product.discountRate : null,
      from: false,
    };
  }
  const surcharge = product.skus.some(
    (candidate) => candidate.salePrice.amount !== product.salePrice.amount,
  );
  return {
    salePrice: product.salePrice,
    listPrice: product.listPrice,
    discountRate: product.discountRate,
    from: surcharge,
  };
}
