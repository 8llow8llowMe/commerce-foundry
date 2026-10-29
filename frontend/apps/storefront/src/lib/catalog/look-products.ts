import { serverFetch } from '@/lib/server-api';
import type { LookProduct } from './look-cart';
import { normalizeProductLooks } from './product-looks';
import type { ProductDetail, ProductLook } from './types';

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
 * `locale` 을 주면 그 언어로 토큰 없이 부른다 — 요청 언어(root params)를 읽을 수 없는 서버 액션(메인 미리보기)용이다.
 */
export async function fetchLookProducts(
  productIds: string[],
  locale?: string,
): Promise<Map<string, LookProduct>> {
  const unique = [...new Set(productIds)];
  const results = await Promise.allSettled(
    unique.map((id) =>
      locale
        ? serverFetch<ProductDetail>(
            `/api/v1/products/${id}`,
            { headers: { 'accept-language': locale } },
            { anonymous: true },
          )
        : serverFetch<ProductDetail>(`/api/v1/products/${id}`),
    ),
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
 * 이 상품이 들어간 보이는 룩(BE #62), 노출 순서로 4개까지. 룩은 덧붙이는 영역이라 실패하거나(404 · 장애)
 * 응답 모양이 어긋나도 빈 목록 · 쓸 수 있는 룩만으로 둔다 — 상품 상세가 깨지지 않게 한다.
 */
export async function fetchProductLooks(productId: string): Promise<ProductLook[]> {
  try {
    const body = await serverFetch<unknown>(`/api/v1/products/${productId}/looks`);
    return normalizeProductLooks(body).slice(0, PRODUCT_LOOKS_LIMIT);
  } catch {
    return [];
  }
}
