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
   * 목록 hover · 포커스 때 바꿔 보일 두 번째 정지 이미지(착용컷 · 디테일, #60 · BE #300). 대표 · 갤러리를 노출 순서로
   * 세어 두 번째이고 영상은 세지 않는다. 정지 이미지가 1장 이하면 null — 전환하지 않는다
   */
  hoverImage: ProductThumbnail | null;
  /** 판매중 옵션 중 최저가 */
  salePrice: Money;
  /** 할인 전 정가. discountRate 와 함께 null 이거나 함께 값이 있다 */
  listPrice: Money | null;
  discountRate: number | null;
  /** true 면 옵션에 따라 가격이 올라간다 — '129,000원~' 으로 표시 */
  hasOptionSurcharge: boolean;
  saleStatus: SaleStatus;
  customOrder: boolean;
  /** 프리오더(예약 판매) 상품인가. 스토어 프리오더 기능이 꺼져 있으면 false. 발송 예정일은 상세에만 온다 */
  preorder: boolean;
};

// GET /api/v1/products/{productId}. 없거나 숨긴 상품은 404 PRODUCT_001 이다.

export type CategoryPathItem = {
  categoryId: string;
  name: string;
};

/** 상세 갤러리 미디어 종류. 화면 분기용 제어 코드라 metadata 객체가 아니라 문자열이다(BE api-design-guide §2-7) */
export type ProductMediaType = 'IMAGE' | 'VIDEO';

/**
 * 상세 갤러리 미디어 하나. 스토어 영상 기능이 켜져 있으면 이미지와 영상이 노출 순서로 섞여 온다.
 * VIDEO 의 url 은 영상 파일, posterUrl 은 재생 전 정지 이미지다(우리 스토리지 URL 만 온다).
 */
export type ProductImage = ProductThumbnail & {
  mediaType: ProductMediaType;
  /** VIDEO 의 포스터 이미지 URL. IMAGE 면 null */
  posterUrl: string | null;
  displayOrder: number;
};

/** 프리오더(예약 판매) 안내. 판매 · 재고 판정은 일반 상품과 같고, 화면에 발송 예정 안내만 더한다 */
export type Preorder = {
  /** 발송 예정일 `yyyy-MM-dd`(순차 발송 시작일, 스토어 현지 날짜). null 이면 '발송일 추후 안내' */
  expectedShipDate: string | null;
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
  /** 갤러리 이미지 · 영상, 노출 순서. 대표 이미지는 들어가지 않는다 */
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
  /** 프리오더 상품이 아니거나 스토어 기능이 꺼져 있으면 null */
  preorder: Preorder | null;
};

// GET /api/v1/products/{productId}/looks — 이 상품이 들어간 보이는 룩(Shop the Look, BE #62).
// 정본은 BE api-design-guide §2-11 · LookSummaryResponse 다. 룩은 전시용 묶음이라 가격 · 재고 · 주문은 구성 상품(SKU) 각각을 따른다.
// 숨긴 룩 · 숨긴 상품은 서버가 빼고 보낸다. 룩 기능 플래그가 꺼져 있으면 `looks: []` 다.

/** 룩 대표 이미지. 상품 이미지와 달리 imageId 가 없다 */
export type LookImage = {
  url: string;
  width: number | null;
  height: number | null;
  /** 평문 — 텍스트로만 쓴다 */
  altText: string | null;
};

export type ProductLook = {
  lookId: string;
  /** 평문 — 텍스트 노드로만 그린다 */
  title: string;
  /**
   * 평문, 개행 · 탭이 들어 있다. 상품별 룩(목록 항목)에는 없고 룩 상세(`GET /api/v1/looks/{lookId}`)에만 온다
   */
  description?: string | null;
  /** 계약상 필수다. 모양이 깨져 오면 정규화(normalizeProductLooks)가 null 로 둔다 */
  image: LookImage | null;
  /**
   * 구성 상품 카드 — 상품 목록 항목(`GET /api/v1/products`)과 같은 모양, 배열 순서 = 구성 순서.
   * 판매 종료 · 품절은 saleStatus 로 남고, 상품이 모두 빠지면 빈 배열이다(그때 "함께 담기" 를 숨긴다)
   */
  products: ProductListItem[];
};

export type ProductLooksResponse = {
  looks: ProductLook[];
};
