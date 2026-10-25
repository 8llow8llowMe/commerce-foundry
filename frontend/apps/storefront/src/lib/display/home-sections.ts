import type {
  DetailSection,
  HeroSection,
  HomeImage,
  HomeLink,
  HomeMediaSlot,
  HomeSection,
  ProductPickSection,
  StorySection,
} from '@cf/ui';
import type { ProductListItem } from '@/lib/catalog/types';
import type {
  DisplayElementItem,
  DisplayLinkItem,
  DisplayMediaItem,
  DisplaySectionItem,
} from './types';

// 메인 전시 API(#63) 응답 → 메인 섹션(HomeSection). 섹션 노출 · 순서는 응답을 따르고, 레이아웃 변형(curtain · panel)과
// 아직 API 로 옮기지 않은 블록(LOOK, #62)은 브랜드 설정(content.home)을 틀로 쓴다. 정본 규칙은 frontend/docs/architecture.md §10.

/** 메인 전시 API 의 상품 픽 — 상품 카드를 응답에서 바로 받는다(브랜드 설정의 상품 픽은 source 로 목록 API 를 부른다) */
export type DisplayProductPickSection = Omit<ProductPickSection, 'source'> & {
  products: ProductListItem[];
};

/** 메인이 그리는 블록 — 브랜드 설정 섹션 또는 전시 API 에서 만든 섹션 */
export type HomeBlock = HomeSection | DisplayProductPickSection;

/** 링크 문구가 비었을 때 쓰는 화면 기본 문구(사전 dict.home) */
export type HomeLinkLabels = {
  /** 히어로 · 스토리 링크 */
  more: string;
  /** 상품 픽 '전체 보기' */
  viewAll: string;
  /** 히어로의 쇼핑 버튼 — 링크가 없거나 다른 곳으로 가면 함께 둔다 */
  shop: string;
};

/** 히어로 보조 버튼 · 링크 없는 히어로의 주 버튼이 가는 곳 */
export const SHOP_PATH = '/shop';

const RELATIVE_PATH = /^\/(?![/\\])[^\s\\]*$/;
const HTTPS_URL = /^https:\/\/[^\s\\/]+[^\s\\]*$/;

/**
 * 링크 주소가 BE 규칙(스토어 안 상대 경로 또는 https)에 맞을 때만 쓴다. BE 가 저장 때 거르지만 화면도 한 번 더 본다 —
 * `//host` · `javascript:` · 백슬래시 · 공백이면 링크를 그리지 않는다.
 */
export function safeHref(url: unknown): string | null {
  if (typeof url !== 'string') return null;
  return RELATIVE_PATH.test(url) || HTTPS_URL.test(url) ? url : null;
}

/** 외부(https) 링크에 붙일 rel — BE 계약상 https 호스트는 가리지 않으므로 FE 가 막는다 */
export function linkRel(href: string): string | undefined {
  return href.startsWith('https://') ? 'noopener noreferrer' : undefined;
}

function toLink(link: DisplayLinkItem | null, fallbackLabel: string): HomeLink | null {
  const href = safeHref(link?.url);
  if (!link || !href) return null;
  return { label: link.label || fallbackLabel, href };
}

/** 사진 한 장. VIDEO 는 스토리 · 디테일에서 재생하지 않고 포스터를 보인다 */
function toImage(media: DisplayMediaItem): HomeImage | null {
  const video = media.mediaType === 'VIDEO';
  const src = video ? media.pcPosterUrl : media.pcUrl;
  if (!src) return null;
  const mobile = (video ? media.mobilePosterUrl : media.mobileUrl) ?? null;
  return {
    src,
    alt: media.altText ?? '',
    ...(mobile && mobile !== src ? { mobileSrc: mobile } : {}),
  };
}

/** 그릴 수 있는 미디어(주소가 있는 것)와 그 사진, 아이템 순서대로 */
function imagesOf(items: DisplayElementItem[]): { media: DisplayMediaItem; image: HomeImage }[] {
  return items.flatMap((item) => {
    if (item.itemType !== 'MEDIA' || !item.media) return [];
    const image = toImage(item.media);
    return image ? [{ media: item.media, image }] : [];
  });
}

function mediaSlots(items: DisplayElementItem[]): HomeMediaSlot[] {
  return items.flatMap((item) => {
    if (item.itemType !== 'MEDIA' || !item.media) return [];
    const image = toImage(item.media);
    return image ? [{ image, placeholder: '', caption: item.caption }] : [];
  });
}

const blockId = (section: DisplaySectionItem) => `display-${section.sectionId}`;

function toHero(section: DisplaySectionItem, labels: HomeLinkLabels): HeroSection | null {
  const [first] = imagesOf(section.items);
  if (!first) return null;
  const { media, image } = first;
  // 히어로는 첫 장만 그린다 — 여러 장 넘기기(교차 전환)는 화보 촬영 후(#59)
  const video =
    media.mediaType === 'VIDEO' && media.pcPosterUrl
      ? { src: media.pcUrl, poster: media.pcPosterUrl }
      : null;
  const shop: HomeLink = { label: labels.shop, href: SHOP_PATH };
  const link = toLink(section.link, labels.more);
  return {
    type: 'HERO',
    id: blockId(section),
    // API 제목은 한 줄 값이다 — 줄 나눔은 화면 폭이 정한다
    title: section.title ? [section.title] : [],
    subtitle: section.subtitle,
    // 모바일 첫 화면에 쇼핑으로 가는 버튼이 늘 있게(#59 완료 기준) — 링크가 없으면 쇼핑이 주 버튼이다
    cta: link ?? shop,
    secondaryCta: link && link.href !== SHOP_PATH ? shop : null,
    desktop: { image: { src: image.src, alt: image.alt }, placeholder: '' },
    mobile: { image: { src: image.mobileSrc ?? image.src, alt: image.alt }, placeholder: '' },
    video,
  };
}

function toProductPick(
  section: DisplaySectionItem,
  labels: HomeLinkLabels,
): DisplayProductPickSection | null {
  const seen = new Set<string>();
  const products = section.items.flatMap((item) => {
    const product = item.itemType === 'PRODUCT' ? item.product : null;
    if (!product || typeof product.productId !== 'string' || seen.has(product.productId)) return [];
    seen.add(product.productId);
    return [product];
  });
  // 숨긴 상품이 빠져 비면 섹션은 응답에 남지만 그리지 않는다
  if (products.length === 0) return null;
  return {
    type: 'PRODUCT_PICK',
    id: blockId(section),
    title: section.title ?? '',
    viewAll: toLink(section.link, labels.viewAll),
    products,
  };
}

function toStory(
  section: DisplaySectionItem,
  layout: StorySection['layout'],
  labels: HomeLinkLabels,
): StorySection | null {
  const media = mediaSlots(section.items);
  const title = section.title ?? '';
  const body = section.body ?? '';
  if (!title && !body && media.length === 0) return null;
  const link = toLink(section.link, labels.more);
  return {
    type: 'STORY',
    id: blockId(section),
    title,
    body,
    links: link ? [link] : [],
    media,
    // 사진 없는 커튼 · 비대칭은 글만 가운데에 둔다. 패널은 사진 없이도 선다
    layout: media.length === 0 && layout !== 'panel' ? 'centered' : layout,
  };
}

function toDetail(section: DisplaySectionItem): DetailSection | null {
  const media = mediaSlots(section.items);
  if (media.length === 0) return null;
  return {
    type: 'DETAIL',
    id: blockId(section),
    title: section.title ?? '',
    body: section.body ?? '',
    // 확대 지점(focus)은 API 에 없다 — 확대 무대 없이 클로즈업 + 캡션을 세로로 놓는다
    frame: null,
    points: media.map((slot) => ({ title: slot.caption ?? '', text: '', focus: null })),
    media,
  };
}

function isSectionItem(value: unknown): value is DisplaySectionItem {
  if (typeof value !== 'object' || value === null) return false;
  const section = value as Partial<DisplaySectionItem>;
  return (
    typeof section.sectionId === 'string' &&
    typeof section.sectionType?.code === 'string' &&
    Array.isArray(section.items)
  );
}

/** 응답 본문에서 섹션 목록을 읽는다. 모양이 다르면 null, 모양이 다른 섹션 하나는 건너뛴다 */
export function readDisplaySections(body: unknown): DisplaySectionItem[] | null {
  if (typeof body !== 'object' || body === null) return null;
  const { sections } = body as { sections?: unknown };
  if (!Array.isArray(sections)) return null;
  return sections.filter(isSectionItem).map((section) => ({
    ...section,
    items: section.items.filter(
      (item): item is DisplayElementItem => typeof item === 'object' && item !== null,
    ),
  }));
}

/**
 * 응답 섹션을 응답 순서대로 메인 블록으로 바꾼다. 비었거나(상품이 모두 빠진 상품 픽 · 사진 없는 히어로 등) 모르는 유형은 건너뛴다.
 * - STORY 의 레이아웃은 브랜드 설정의 STORY 순서를 따른다(n 번째 STORY → 설정의 n 번째 STORY 레이아웃, 없으면 asymmetric)
 * - LOOK 은 룩 API(#62) 전까지 응답 자리에 브랜드 설정의 LOOK 을 한 번 그린다(설정에 없으면 건너뛴다)
 */
export function toHomeBlocks(
  sections: DisplaySectionItem[],
  template: HomeSection[],
  labels: HomeLinkLabels,
): HomeBlock[] {
  const storyLayouts = template.flatMap((section) =>
    section.type === 'STORY' ? [section.layout] : [],
  );
  const look = template.find((section) => section.type === 'LOOK') ?? null;
  let storyIndex = 0;
  let lookUsed = false;

  return sections.flatMap((section): HomeBlock[] => {
    let block: HomeBlock | null = null;
    switch (section.sectionType.code) {
      case 'HERO':
        block = toHero(section, labels);
        break;
      case 'PRODUCT_PICK':
        block = toProductPick(section, labels);
        break;
      case 'STORY':
        block = toStory(section, storyLayouts[storyIndex++] ?? 'asymmetric', labels);
        break;
      case 'DETAIL':
        block = toDetail(section);
        break;
      case 'LOOK':
        if (look && !lookUsed) {
          lookUsed = true;
          block = look;
        }
        break;
    }
    return block ? [block] : [];
  });
}

/**
 * 메인에 그릴 블록. 전시 API 를 못 읽었거나(null) 모양이 다르거나 그릴 섹션이 하나도 없으면 브랜드 설정(안전한 기본)이다 —
 * 운영 데이터가 아직 없는 처음 배포 · 장애에도 메인이 빈 화면이 되지 않는다.
 */
export function resolveHomeBlocks(
  body: unknown,
  template: HomeSection[],
  labels: HomeLinkLabels,
): HomeBlock[] {
  const sections = body === null ? null : readDisplaySections(body);
  if (!sections) return template;
  const blocks = toHomeBlocks(sections, template, labels);
  return blocks.length > 0 ? blocks : template;
}
