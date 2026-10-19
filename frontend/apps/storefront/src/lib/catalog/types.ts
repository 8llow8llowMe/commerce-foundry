import type { Money } from '@cf/api-client';

// product-service 고객 조회 API 계약 (GET /api/v1/categories · /api/v1/products).
// 정본은 BE 의 DTO(@Schema)다. 식별자는 JS 안전 정수를 넘으므로 항상 string 이다.

export type CategoryItem = {
  categoryId: string;
  name: string;
  displayOrder: number;
  /** 없으면 빈 배열. 숨긴 카테고리는 하위까지 빠져서 온다 */
  children: CategoryItem[];
};

export type CategoryTreeResponse = {
  categories: CategoryItem[];
};

/** 응답의 판매 상태. 저장 상태가 아니라 SKU 재고까지 본 판정이다 */
export type SaleStatusCode = 'ON_SALE' | 'SOLD_OUT' | 'STOP_SELLING';

export type SaleStatus = {
  code: SaleStatusCode;
  /** 표시 문구 (예: '품절') — 화면은 이 값을 그대로 쓴다 */
  name: string;
  description: string;
};

export type ProductThumbnail = {
  imageId: string;
  url: string;
  width: number | null;
  height: number | null;
  altText: string | null;
};

export type ProductListItem = {
  productId: string;
  name: string;
  thumbnail: ProductThumbnail | null;
  /** 판매중 옵션 중 최저가 */
  salePrice: Money;
  /** 할인 전 정가. discountRate 와 함께 null 이거나 함께 값이 있다 */
  listPrice: Money | null;
  discountRate: number | null;
  /** true 면 옵션에 따라 가격이 올라간다 — '129,000원~' 으로 표시 */
  hasOptionSurcharge: boolean;
  saleStatus: SaleStatus;
  customOrder: boolean;
};
