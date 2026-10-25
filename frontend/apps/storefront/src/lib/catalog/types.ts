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
  /**
   * 목록 hover 때 바꿔 보일 두 번째 이미지(착용컷 · 디테일, #60). BE 목록 계약에 아직 없다 — #300 초안, 이름
   * 미확정. BE 가 이름을 정하면 맞춘다. 없거나 null 이면 전환하지 않는다
   */
  hoverImage?: ProductThumbnail | null;
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

// GET /api/v1/products/{productId}. 없거나 숨긴 상품은 404 PRODUCT_001 이다.

export type CategoryPathItem = {
  categoryId: string;
  name: string;
};

export type ProductImage = ProductThumbnail & {
  displayOrder: number;
};

export type ProductOptionValue = {
  optionValueId: string;
  name: string;
  displayOrder: number;
};

export type ProductOptionGroup = {
  optionGroupId: string;
  name: string;
  displayOrder: number;
  values: ProductOptionValue[];
};

export type ProductSku = {
  skuId: string;
  /** optionGroups 순서로 정렬된 옵션값 id. 옵션 없는 상품은 빈 배열인 SKU 1개다 */
  optionValueIds: string[];
  additionalPrice: Money;
  /** 기본 판매가 + 추가금 — 서버가 계산한 값 */
  salePrice: Money;
  saleStatus: SaleStatus;
};

export type CustomOrderField = {
  /** 주문 입력값의 키이자 검증 오류 fieldErrors[].field */
  fieldKey: string;
  label: string;
  inputType: 'NUMBER' | 'TEXT';
  unit: string | null;
  required: boolean;
  min: number | null;
  /** NUMBER 는 최대값, TEXT 는 최대 글자 수 */
  max: number | null;
  step: number | null;
  helpText: string | null;
};

export type CustomOrder = {
  notice: string | null;
  leadTimeDays: number | null;
  fields: CustomOrderField[];
};

export type ProductDetail = {
  productId: string;
  name: string;
  summary: string | null;
  /** 최상위 → 대표 카테고리 */
  categoryPath: CategoryPathItem[];
  thumbnail: ProductThumbnail | null;
  /** 갤러리 이미지, 노출 순서 */
  images: ProductImage[];
  /** 서버가 정제하지 않은 원문 — 반드시 sanitizeProductHtml 을 거쳐 넣는다 */
  descriptionHtml: string | null;
  salePrice: Money;
  listPrice: Money | null;
  discountRate: number | null;
  saleStatus: SaleStatus;
  /** null 이면 제한 없음 */
  maxQuantityPerOrder: number | null;
  optionGroups: ProductOptionGroup[];
  skus: ProductSku[];
  /** 맞춤 제작 상품이 아니거나 스토어 기능이 꺼져 있으면 null */
  customOrder: CustomOrder | null;
};

// GET /api/v1/products/{productId}/looks — 이 상품이 들어간 룩(Shop the Look). BE #62 초안 계약이다.
// 룩은 전시용 묶음이라 가격 · 재고 · 주문은 구성 상품(SKU) 각각을 따른다. 확정되면 여기를 맞춘다.

export type LookItem = {
  productId: string;
  name: string;
  thumbnail: ProductThumbnail | null;
  salePrice: Money;
  saleStatus: SaleStatus;
};

export type ProductLook = {
  lookId: string;
  title: string;
  description: string | null;
  /** 대표 착장 화보 */
  image: ProductThumbnail | null;
  /** 노출 순서. 비노출 · 판매 종료 상품의 처리 규칙은 #62 에서 정한다 */
  items: LookItem[];
};

export type ProductLooksResponse = {
  looks: ProductLook[];
};
