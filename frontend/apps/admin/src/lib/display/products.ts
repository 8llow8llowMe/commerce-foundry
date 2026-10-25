import type { ProductListItem } from '@/lib/product/query';
import type { ProductFormItem } from './form';
import { ITEM_RANGES, type SectionTypeCode } from './types';

export type AddProductResult =
  { ok: true; products: ProductFormItem[] } | { ok: false; reason: 'duplicate' | 'full' };

/** 검색 결과 상품을 맨 뒤에 더한다. 이미 있거나 상한(BE 유형 규칙)에 닿았으면 더하지 않는다 */
export function addProduct(
  products: readonly ProductFormItem[],
  product: Pick<ProductListItem, 'productId' | 'name' | 'visible' | 'availability'>,
  type: SectionTypeCode,
): AddProductResult {
  if (products.some((item) => item.productId === product.productId)) {
    return { ok: false, reason: 'duplicate' };
  }
  if (products.length >= ITEM_RANGES[type].product[1]) return { ok: false, reason: 'full' };
  return {
    ok: true,
    products: [
      ...products,
      {
        productId: product.productId,
        name: product.name,
        visible: product.visible,
        availability: product.availability,
        caption: null,
      },
    ],
  };
}

/**
 * 운영자에게 알릴 상품 상태 — 숨김이면 메인에서 빠지고, 판매중이 아니면(품절 · 판매 종료) 메인에 보이지만 살 수 없다.
 * 판매 가능 상태는 이번에 검색해서 더한 상품만 안다. 없으면 null.
 */
export function productNotice(item: ProductFormItem): string | null {
  if (item.name === null) return '찾을 수 없는 상품입니다. 빼고 저장해 주세요.';
  if (!item.visible) return '숨긴 상품이라 메인에 보이지 않습니다.';
  if (item.availability && item.availability.code !== 'ON_SALE') {
    return `${item.availability.name} 상품입니다. 메인에 보이지만 지금은 살 수 없습니다.`;
  }
  return null;
}
