import type { ProductListItem } from '@/lib/product/query';
import type { ProductFormItem } from './form';

export type AddProductResult =
  { ok: true; products: ProductFormItem[] } | { ok: false; reason: 'duplicate' | 'full' };

/** 검색 결과 상품을 맨 뒤에 더한다. 이미 있거나 상한(`max`, BE 규칙 — 상품 픽 · 룩 모두 20개)에 닿았으면 더하지 않는다 */
export function addProduct(
  products: readonly ProductFormItem[],
  product: Pick<ProductListItem, 'productId' | 'name' | 'visible' | 'availability'>,
  max: number,
): AddProductResult {
  if (products.some((item) => item.productId === product.productId)) {
    return { ok: false, reason: 'duplicate' };
  }
  if (products.length >= max) return { ok: false, reason: 'full' };
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

/** 삭제된 상품 줄의 안내(룩, BE #424) — 이미 구성된 상품이 삭제되면 룩에 남지만 그대로는 저장할 수 없다 */
export const DELETED_PRODUCT_NOTICE = '삭제된 상품 — 빼야 저장할 수 있습니다.';

/**
 * 운영자에게 알릴 상품 상태 — 숨김이면 고객 화면(`surface` — 메인 · 룩)에서 빠지고, 판매중이 아니면(품절 · 판매 종료) 보이지만 살 수 없다.
 * 판매 가능 상태를 모르면(메인 전시 응답에는 노출 여부만 있다) 알리지 않는다.
 */
export function productNotice(item: ProductFormItem, surface = '메인'): string | null {
  if (item.name === null) return '찾을 수 없는 상품입니다. 빼고 저장해 주세요.';
  if (item.deleted) return DELETED_PRODUCT_NOTICE;
  if (!item.visible) return `숨긴 상품이라 ${surface}에 보이지 않습니다.`;
  if (item.availability && item.availability.code !== 'ON_SALE') {
    return `${item.availability.name} 상품입니다. ${surface}에 보이지만 지금은 살 수 없습니다.`;
  }
  return null;
}
