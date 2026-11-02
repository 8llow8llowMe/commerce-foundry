// product-service 어드민 메인 전시 API 계약(BE #63, `backend/docs/api-design-guide.md` §2-9).
// 정본은 BE DTO(@Schema) — DisplaySectionAdminResponse · DisplayElementAdminItem · DisplaySectionUpsertRequest 등이다.
// 식별자는 JS 안전 정수를 넘으므로 항상 string 이다. 문구(제목 · 부제 · 본문 · 링크 문구 · 대체 텍스트)는 모두 평문이다.

/** 섹션 목록 · 생성은 이 경로, 단건은 `/{sectionId}`, 순서 변경은 `/order` */
export const DISPLAY_SECTIONS_PATH = '/api/v1/admin/displays/sections';
export const DISPLAY_ORDER_PATH = `${DISPLAY_SECTIONS_PATH}/order`;
/** 저장하지 않는 미리보기(#340) — 본문 `{sections}`, 응답은 고객 메인 응답과 같은 모양 */
export const DISPLAY_PREVIEW_PATH = '/api/v1/admin/displays/preview';

export const SECTION_TYPES = ['HERO', 'PRODUCT_PICK', 'STORY', 'LOOK', 'DETAIL'] as const;
export type SectionTypeCode = (typeof SECTION_TYPES)[number];

/** BE `CodeNameDescriptionMetadata` — 화면은 name 을 보이고 description 은 보조 설명이다 */
export type CodeName<Code extends string = string> = {
  code: Code | (string & {});
  name: string;
  description: string;
};

export type DisplayMediaAdmin = {
  mediaType: CodeName<'IMAGE' | 'VIDEO'>;
  pcUrl: string;
  mobileUrl: string | null;
  /** VIDEO 면 있고 IMAGE 면 null */
  pcPosterUrl: string | null;
  mobilePosterUrl: string | null;
  altText: string | null;
};

export type DisplayProductAdmin = {
  productId: string;
  /** 상품 행이 없으면(데이터 오류) null */
  name: string | null;
  /** false 면 고객 메인에서 이 상품이 빠진다 */
  visible: boolean;
};

export type DisplayElementAdmin = {
  itemType: CodeName<'MEDIA' | 'PRODUCT'>;
  media: DisplayMediaAdmin | null;
  product: DisplayProductAdmin | null;
  caption: string | null;
};

export type LiveStatusCode = 'LIVE' | 'HIDDEN' | 'SCHEDULED' | 'ENDED';

/** 어드민 섹션 — 아이템 아이디 · 내부 순서 번호는 오지 않는다(#305). 순서는 목록의 배열 순서다 */
export type DisplaySectionAdmin = {
  sectionId: string;
  sectionType: CodeName<SectionTypeCode>;
  title: string | null;
  subtitle: string | null;
  body: string | null;
  linkUrl: string | null;
  linkLabel: string | null;
  visible: boolean;
  /** UTC ISO. 없으면 제한 없음 */
  startAt: string | null;
  endAt: string | null;
  /** 서버 시각 기준 지금 노출 상태 — 고객 메인과 같은 판정 */
  liveStatus: CodeName<LiveStatusCode>;
  items: DisplayElementAdmin[];
};

export type DisplaySectionList = { sections: DisplaySectionAdmin[] };

/** 생성 · 수정(전체 교체) 본문 — 아이템은 받은 순서가 섹션 안의 순서다 */
export type DisplayItemRequest =
  | {
      itemType: 'MEDIA';
      media: {
        mediaType: string;
        pcUrl: string;
        mobileUrl: string | null;
        pcPosterUrl: string | null;
        mobilePosterUrl: string | null;
        altText: string | null;
      };
      caption: string | null;
    }
  | { itemType: 'PRODUCT'; productId: string; caption: string | null };

export type DisplaySectionRequest = {
  sectionType: string;
  title: string | null;
  subtitle: string | null;
  body: string | null;
  linkUrl: string | null;
  linkLabel: string | null;
  visible: boolean;
  startAt: string | null;
  endAt: string | null;
  items: DisplayItemRequest[];
};

export type DisplayOrderRequest = { sectionIds: string[] };

/** BE 요청 검증 · 섹션 규칙의 상한(`DisplaySectionUpsertRequest` · `DisplaySectionType` · `DisplaySectionProcessor`) */
export const LIMITS = {
  title: 200,
  subtitle: 200,
  body: 2000,
  linkUrl: 500,
  linkLabel: 50,
  altText: 200,
  /** 미디어 주소(pcUrl · mobileUrl · 포스터) */
  mediaUrl: 500,
  caption: 200,
  sections: 50,
} as const;

/** 유형별 아이템 수(하한 · 상한 포함). 상한이 0 이면 그 아이템을 받지 않는다 — BE `DisplaySectionType` */
export const ITEM_RANGES: Record<
  SectionTypeCode,
  { media: [number, number]; product: [number, number] }
> = {
  HERO: { media: [1, 10], product: [0, 0] },
  PRODUCT_PICK: { media: [0, 0], product: [1, 20] },
  STORY: { media: [0, 10], product: [0, 0] },
  LOOK: { media: [1, 10], product: [0, 20] },
  DETAIL: { media: [1, 10], product: [0, 0] },
};

/**
 * 미디어를 주소로 넣고 · 빼는 유형(#419 · #71) — 미디어를 받는 모든 유형이다. 전시용 업로드 API 가 없어
 * 쇼핑몰 이미지 저장소에 이미 올린 이미지의 주소를 넣는다(룩 관리 화면과 같다). 저장소 주소인지는 서버가 본다(`PRODUCT_054`).
 */
export const MEDIA_URL_TYPES: readonly SectionTypeCode[] = SECTION_TYPES.filter(
  (type) => ITEM_RANGES[type].media[1] > 0,
);

/** PC · 모바일 사진을 따로 넣는 유형 — 메인이 칸을 `<picture>` 로 나눠 그린다(`HeroSection`) */
export const MOBILE_MEDIA_TYPES: readonly SectionTypeCode[] = ['HERO'];

/** 미디어마다 설명 문구(caption)를 쓰는 유형 — 디테일은 클로즈업마다 설명 한 줄을 보인다(`CraftZoom`) */
export const CAPTION_TYPES: readonly SectionTypeCode[] = ['DETAIL'];

/**
 * 제목 너비 한도(#71, #57 댓글 §4 "모든 제목은 어떤 폭에서도 2줄 이내") — 한글 한 글자를 1, 영문 · 숫자 · 공백 · 기호를 0.5 로 센다(`titleWidth`).
 * 스토어프론트 메인에서 그 유형의 제목 칸이 가장 좁을 때(360px 휴대폰 · 1024px PC) 한 줄에 들어가는 한글 수 × 2 다.
 * - 히어로 · 디테일: display 40px, 칸 328px → 한 줄 8자
 * - 상품 픽 · 룩: title 28 / 48px — 룩 PC 칸(5/12 ≈ 380px)과 상품 픽 모바일(오른쪽 링크와 나눠 씀)이 가장 좁다 → 한 줄 8자
 * - 스토리: curtain 레이아웃 PC 칸이 220px · 48px → 한 줄 4.5자. 레이아웃은 브랜드 설정이라 가장 좁은 칸에 맞춘다
 * 글꼴 폭에 따라 조금 다를 수 있어 미리보기로 확인하게 안내한다. 서버는 200자(`LIMITS.title`)만 본다.
 */
export const TITLE_WIDTH: Record<SectionTypeCode, number> = {
  HERO: 16,
  PRODUCT_PICK: 16,
  STORY: 9,
  LOOK: 16,
  DETAIL: 16,
};

/** 업로드 없이 만들 수 있는 유형 — 미디어 하한이 0 이거나 미디어를 주소로 넣는 유형(지금은 모든 유형) */
export const CREATABLE_TYPES = SECTION_TYPES.filter(
  (type) => ITEM_RANGES[type].media[0] === 0 || MEDIA_URL_TYPES.includes(type),
);

/** 유형 이름 — 섹션을 새로 만들 때(아직 응답이 없을 때)만 쓴다. 목록은 서버 `sectionType.name` 을 보인다 */
export const TYPE_LABELS: Record<SectionTypeCode, string> = {
  HERO: '히어로',
  PRODUCT_PICK: '상품 픽',
  STORY: '스토리',
  LOOK: '룩',
  DETAIL: '디테일',
};

export function isSectionType(code: string): code is SectionTypeCode {
  return (SECTION_TYPES as readonly string[]).includes(code);
}
