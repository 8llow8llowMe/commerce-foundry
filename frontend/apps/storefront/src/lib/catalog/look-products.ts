import { serverFetch } from '@/lib/server-api';
import type { LookProduct } from './look-cart';
import type { ProductDetail, ProductLook, ProductLooksResponse } from './types';

/** 상세 SHOP THE LOOK 에 보여 줄 최대 룩 수 */
export const PRODUCT_LOOKS_LIMIT = 4;

/** 클라이언트로 넘길 만큼만 — 설명 HTML · 갤러리는 빼고, 맞춤 제작은 여부만 */
export function toLookProduct(detail: ProductDetail): LookProduct {
  return {
    productId: detail.productId,
    name: detail.name,
    thumbnail: detail.thumbnail,
    salePrice: detail.salePrice,
    saleStatus: detail.saleStatus,
    optionGroups: detail.optionGroups,
    skus: detail.skus,
    customOrder: detail.customOrder !== null,
  };
}

/**
 * 구성품의 옵션 · SKU 는 룩 응답에 없어 상품 상세 API 로 하나씩 받는다. 같은 상품은 한 번만 부르고,
 * 못 받은 상품(숨김 · 삭제 · 장애)은 빠진다 — 룩 하나 때문에 페이지가 깨지지 않게 한다.
 */
export async function fetchLookProducts(productIds: string[]): Promise<Map<string, LookProduct>> {
  const unique = [...new Set(productIds)];
  const results = await Promise.allSettled(
    unique.map((id) => serverFetch<ProductDetail>(`/api/v1/products/${id}`)),
  );
  const products = new Map<string, LookProduct>();
  for (const result of results) {
    if (result.status === 'fulfilled') {
      products.set(result.value.productId, toLookProduct(result.value));
    }
  }
  return products;
}

/**
 * 이 상품이 들어간 룩(#62 초안). 룩은 덧붙이는 영역이라 API 가 아직 없거나(404) 실패해도 빈 목록으로 둔다.
 */
export async function fetchProductLooks(productId: string): Promise<ProductLook[]> {
  try {
    const { looks } = await serverFetch<ProductLooksResponse>(
      `/api/v1/products/${productId}/looks`,
    );
    return looks.slice(0, PRODUCT_LOOKS_LIMIT);
  } catch {
    return [];
  }
}
