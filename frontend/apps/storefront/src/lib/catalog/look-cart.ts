import { resolveSku, type OptionSelection } from './option-selection';
import type { ProductDetail } from './types';

/** Shop the Look 구성품 한 줄에 필요한 상세 데이터(옵션 · SKU 까지) */
export type LookProduct = Pick<
  ProductDetail,
  'productId' | 'name' | 'thumbnail' | 'salePrice' | 'saleStatus' | 'optionGroups' | 'skus'
> & {
  /** 맞춤 제작 상품은 치수를 받아야 해서 함께 담기에서 빼고 상세로 보낸다 */
  customOrder: boolean;
};

export type LookPick = {
  product: LookProduct;
  /** 함께 담기에 넣는가 */
  included: boolean;
  selection: OptionSelection;
};

/** 장바구니(#26)에 한 줄씩 넘길 값. 수량은 룩 단위라 1 이다 */
export type LookCartLine = { productId: string; skuId: string; quantity: 1 };

export type LookCartResult = {
  lines: LookCartLine[];
  /** 담기로 했지만 옵션을 다 고르지 않았거나 고른 조합을 살 수 없는 상품 이름 */
  incomplete: string[];
};

/** 함께 담기에 넣을 수 있는 상품인가 — 판매중이고 맞춤 제작이 아니다 */
export function lookPickable(product: LookProduct): boolean {
  return product.saleStatus.code === 'ON_SALE' && !product.customOrder;
}

/**
 * 함께 담기로 보낼 SKU 줄. 고른 상품마다 옵션 조합으로 SKU 를 찾고, 판매중 SKU 만 줄이 된다.
 * 하나라도 incomplete 가 있으면 화면은 담기를 막는다(일부만 담기지 않게).
 */
export function lookCartLines(picks: LookPick[]): LookCartResult {
  const lines: LookCartLine[] = [];
  const incomplete: string[] = [];
  for (const { product, included, selection } of picks) {
    if (!included || !lookPickable(product)) continue;
    const sku = resolveSku(product.optionGroups, product.skus, selection);
    if (sku && sku.saleStatus.code === 'ON_SALE') {
      lines.push({ productId: product.productId, skuId: sku.skuId, quantity: 1 });
    } else {
      incomplete.push(product.name);
    }
  }
  return { lines, incomplete };
}
