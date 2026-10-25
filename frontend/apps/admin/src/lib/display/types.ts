// product-service 어드민 메인 전시 API 계약(BE #63, `backend/docs/api-design-guide.md` §2-9).
// 정본은 BE DTO(@Schema) — DisplaySectionAdminResponse · DisplayElementAdminItem · DisplaySectionUpsertRequest 등이다.
// 식별자는 JS 안전 정수를 넘으므로 항상 string 이다. 문구(제목 · 부제 · 본문 · 링크 문구 · 대체 텍스트)는 모두 평문이다.

/** 섹션 목록 · 생성은 이 경로, 단건은 `/{sectionId}`, 순서 변경은 `/order` */
export const DISPLAY_SECTIONS_PATH = '/api/v1/admin/displays/sections';
export const DISPLAY_ORDER_PATH = `${DISPLAY_SECTIONS_PATH}/order`;

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

/** 업로드(#9) 없이 만들 수 있는 유형 — 미디어 하한이 0 인 것. LOOK 은 룩 기능(#62) 전까지 편집하지 않는다 */
export const CREATABLE_TYPES = SECTION_TYPES.filter(
  (type) => type !== 'LOOK' && ITEM_RANGES[type].media[0] === 0,
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
