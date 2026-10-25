import type {
  DisplayElementAdmin,
  DisplaySectionAdmin,
  LiveStatusCode,
  SectionTypeCode,
} from './types';

// 테스트용 가짜 섹션 — 실데이터를 쓰지 않는다(공개 저장소).

const TYPE_NAMES: Record<SectionTypeCode, string> = {
  HERO: '히어로',
  PRODUCT_PICK: '상품 픽',
  STORY: '스토리',
  LOOK: '룩',
  DETAIL: '디테일',
};

const LIVE_NAMES: Record<LiveStatusCode, string> = {
  LIVE: '노출 중',
  HIDDEN: '숨김',
  SCHEDULED: '예정',
  ENDED: '종료',
};

export function mediaItem(
  overrides: Partial<NonNullable<DisplayElementAdmin['media']>> = {},
  caption: string | null = null,
): DisplayElementAdmin {
  return {
    itemType: { code: 'MEDIA', name: '미디어', description: '' },
    media: {
      mediaType: { code: 'IMAGE', name: '이미지', description: '' },
      pcUrl: 'https://cdn.example.test/displays/a-pc.jpg',
      mobileUrl: null,
      pcPosterUrl: null,
      mobilePosterUrl: null,
      altText: '샘플 사진',
      ...overrides,
    },
    product: null,
    caption,
  };
}

export function productItem(
  productId: string,
  name: string | null = `상품 ${productId}`,
  visible = true,
  caption: string | null = null,
): DisplayElementAdmin {
  return {
    itemType: { code: 'PRODUCT', name: '상품', description: '' },
    media: null,
    product: { productId, name, visible },
    caption,
  };
}

export function section(
  type: SectionTypeCode,
  overrides: Partial<DisplaySectionAdmin> = {},
  live: LiveStatusCode = 'LIVE',
): DisplaySectionAdmin {
  return {
    sectionId: '4611686018427387904',
    sectionType: { code: type, name: TYPE_NAMES[type], description: '' },
    title: '샘플 제목',
    subtitle: null,
    body: null,
    linkUrl: null,
    linkLabel: null,
    visible: true,
    startAt: null,
    endAt: null,
    liveStatus: { code: live, name: LIVE_NAMES[live], description: '' },
    items: [],
    ...overrides,
  };
}
