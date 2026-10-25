import type { Money } from '@cf/api-client';
import type { SaleStatus } from '@/lib/catalog/types';

// product-service 고객 메인 전시 API 계약 (GET /api/v1/displays/main, #63).
// 정본은 BE 의 DTO(@Schema) — DisplayMainResponse · DisplaySectionItem · DisplayElementItem 등이다.
// 식별자는 JS 안전 정수를 넘으므로 항상 string 이다. 문구(title · subtitle · body · label · caption · altText)는 모두 평문이다.

/** 섹션 유형 — 화면은 code 로 블록 레이아웃을 고른다. 모르는 code 가 와도 깨지지 않게 string 도 받는다 */
export type DisplaySectionTypeCode = 'HERO' | 'PRODUCT_PICK' | 'STORY' | 'LOOK' | 'DETAIL';

export type DisplaySectionType = {
  code: DisplaySectionTypeCode | (string & {});
  name: string;
  description: string;
};

export type DisplayLinkItem = {
  /** '/' 로 시작하는 스토어 안 상대 경로 또는 https 주소 */
  url: string;
  /** 없으면 null — 화면 기본 문구를 쓴다 */
  label: string | null;
};

export type DisplayMediaItem = {
  mediaType: 'IMAGE' | 'VIDEO' | (string & {});
  pcUrl: string;
  /** 없으면 null — pcUrl 을 쓴다 */
  mobileUrl: string | null;
  /** VIDEO 면 항상 있고 IMAGE 면 null */
  pcPosterUrl: string | null;
  /** 없으면 null — pcPosterUrl 을 쓴다 */
  mobilePosterUrl: string | null;
  altText: string | null;
};

/** 상품 목록 항목(ProductListItem)과 같은 모양 · 값이다 — BE DisplayProductCardParityTest 가 두 응답을 비교한다 */
export type DisplayProductThumbnailItem = {
  imageId: string;
  url: string;
  width: number | null;
  height: number | null;
  altText: string | null;
};

export type DisplayProductItem = {
  productId: string;
  name: string;
  thumbnail: DisplayProductThumbnailItem | null;
  /** 상품 목록 항목과 같은 hover 용 두 번째 정지 이미지(BE #300). 정지 이미지가 1장 이하면 null */
  hoverImage: DisplayProductThumbnailItem | null;
  salePrice: Money;
  listPrice: Money | null;
  discountRate: number | null;
  hasOptionSurcharge: boolean;
  saleStatus: SaleStatus;
  customOrder: boolean;
  preorder: boolean;
};

export type DisplayElementItem = {
  itemId: string;
  itemType: 'MEDIA' | 'PRODUCT' | (string & {});
  /** itemType 이 MEDIA 가 아니면 null */
  media: DisplayMediaItem | null;
  /** itemType 이 PRODUCT 가 아니면 null */
  product: DisplayProductItem | null;
  caption: string | null;
};

export type DisplaySectionItem = {
  sectionId: string;
  sectionType: DisplaySectionType;
  title: string | null;
  subtitle: string | null;
  /** 개행 포함 가능 */
  body: string | null;
  link: DisplayLinkItem | null;
  /** 섹션 안의 순서. 숨긴 상품의 아이템은 빠진다 — 상품 픽이 빈 배열로 올 수 있다 */
  items: DisplayElementItem[];
};

export type DisplayMainResponse = {
  /** 노출 순서 → 아이디 오름차순. 보이는 섹션이 없으면 빈 배열 */
  sections: DisplaySectionItem[];
};
