import type { ProductDetail, ProductListItem, ProductThumbnail } from '@/lib/catalog/types';
import type {
  DisplayElementItem,
  DisplayMainResponse,
  DisplayMediaItem,
  DisplaySectionItem,
} from './types';

// 어드민 메인 전시 미리보기(#71). 어드민 편집 화면이 iframe 으로 연 `/preview/main` 에 저장 전 편집 상태를 postMessage 로 넘기고,
// 스토어프론트가 서버에서 메인과 같은 블록으로 그린다. 저장하지 않으므로 공개 메인 · 전시 캐시는 그대로다.
// 메시지 모양의 정본은 이 파일이다 — 어드민 `lib/display/preview.ts` 가 같은 모양을 보낸다. 규칙은 frontend/docs/architecture.md §10.

/** iframe → 어드민: 받을 준비가 됐다(어드민은 이것을 받고 처음 초안을 보낸다) */
export const PREVIEW_READY = 'cf:main-preview:ready';
/** 어드민 → iframe: 지금 편집 상태 */
export const PREVIEW_DRAFT = 'cf:main-preview:draft';

/**
 * 받은 메시지가 부모 창(어드민)의 편집 상태인가 — 허용한 어드민 origin 과 부모 창이 보낸 것만 믿는다.
 * 다른 창 · 다른 origin 이 보낸 같은 모양의 메시지는 그리지 않는다.
 */
export function isPreviewDraft(
  event: Pick<MessageEvent, 'origin' | 'source' | 'data'>,
  adminOrigin: string,
  parent: MessageEventSource,
): boolean {
  if (event.origin !== adminOrigin || event.source !== parent) return false;
  const data: unknown = event.data;
  return (
    typeof data === 'object' && data !== null && (data as { type?: unknown }).type === PREVIEW_DRAFT
  );
}

/** 미리보기 섹션 한 개 — 어드민 생성 · 수정 본문(`DisplaySectionRequest`)에 아이디만 더한 모양 */
export type PreviewItem =
  | {
      itemType: 'MEDIA';
      media: Omit<DisplayMediaItem, 'mediaType'> & { mediaType: string };
      caption: string | null;
    }
  | { itemType: 'PRODUCT'; productId: string; caption: string | null };

export type PreviewSection = {
  sectionId: string;
  sectionType: string;
  title: string | null;
  subtitle: string | null;
  body: string | null;
  linkUrl: string | null;
  linkLabel: string | null;
  visible: boolean;
  /** UTC ISO. null 이면 제한 없음 */
  startAt: string | null;
  endAt: string | null;
  items: PreviewItem[];
};

/**
 * 섹션 · 아이템은 BE 상한(섹션 50 · 섹션당 아이템 30)과 같다. 상품은 상품 픽 3개(각 20) 분량까지만 카드를 받고, 한 번에 10개씩 부른다 —
 * 서버 액션은 인증 · 속도 제한 없이 누구나 부를 수 있어 요청 한 번이 일으키는 게이트웨이 조회를 묶어 둔다. 넘는 것은 버린다
 */
export const PREVIEW_LIMITS = { sections: 50, items: 30, products: 60, concurrency: 10 } as const;

/** 상품 아이디는 게이트웨이 경로에 들어가므로 숫자만 받는다(Long) */
const PRODUCT_ID = /^[1-9]\d{0,18}$/;
const SECTION_ID = /^[\w-]{1,64}$/;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() !== '' ? value : null;

/** 미디어 주소는 http(s) 만 그린다 — 저장된 주소는 BE 가 스토리지 공개 주소로 판정했지만 이 경로는 저장을 거치지 않는다 */
function mediaUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? value : null;
  } catch {
    return null;
  }
}

function readItem(value: unknown): PreviewItem | null {
  if (!isRecord(value)) return null;
  const caption = text(value.caption);
  if (value.itemType === 'PRODUCT') {
    const { productId } = value;
    return typeof productId === 'string' && PRODUCT_ID.test(productId)
      ? { itemType: 'PRODUCT', productId, caption }
      : null;
  }
  if (value.itemType === 'MEDIA' && isRecord(value.media)) {
    const media = value.media;
    const pcUrl = mediaUrl(media.pcUrl);
    if (!pcUrl || typeof media.mediaType !== 'string') return null;
    return {
      itemType: 'MEDIA',
      media: {
        mediaType: media.mediaType,
        pcUrl,
        mobileUrl: mediaUrl(media.mobileUrl),
        pcPosterUrl: mediaUrl(media.pcPosterUrl),
        mobilePosterUrl: mediaUrl(media.mobilePosterUrl),
        altText: text(media.altText),
      },
      caption,
    };
  }
  return null;
}

function readSection(value: unknown): PreviewSection | null {
  if (!isRecord(value)) return null;
  const { sectionId, sectionType, items } = value;
  if (typeof sectionId !== 'string' || !SECTION_ID.test(sectionId)) return null;
  if (typeof sectionType !== 'string' || !Array.isArray(items)) return null;
  return {
    sectionId,
    sectionType,
    title: text(value.title),
    subtitle: text(value.subtitle),
    body: text(value.body),
    linkUrl: text(value.linkUrl),
    linkLabel: text(value.linkLabel),
    visible: value.visible === true,
    startAt: text(value.startAt),
    endAt: text(value.endAt),
    items: items
      .slice(0, PREVIEW_LIMITS.items)
      .map(readItem)
      .filter((item) => item !== null),
  };
}

/**
 * 메시지 본문 → 미리보기 섹션(배열 순서 = 노출 순서). 모양이 다르면 null, 모양이 다른 섹션 · 아이템 하나는 버린다.
 * 문구는 평문으로만 그리고(텍스트 노드) 링크는 메인과 같이 `safeHref` 를 한 번 더 거친다.
 */
export function readPreviewDraft(value: unknown): PreviewSection[] | null {
  if (!isRecord(value) || !Array.isArray(value.sections)) return null;
  return value.sections
    .slice(0, PREVIEW_LIMITS.sections)
    .map(readSection)
    .filter((section) => section !== null);
}

/**
 * 고객 메인과 같은 노출 판정(BE `liveStatusAt`) — `visible` 이고 `startAt ≤ now < endAt`(비면 제한 없음).
 * 읽을 수 없는 시각이면 보이지 않는다고 본다.
 */
export function isLiveAt(section: PreviewSection, now: number): boolean {
  if (!section.visible) return false;
  const start = section.startAt === null ? -Infinity : Date.parse(section.startAt);
  const end = section.endAt === null ? Infinity : Date.parse(section.endAt);
  if (Number.isNaN(start) || Number.isNaN(end)) return false;
  return start <= now && now < end;
}

/** 카드를 받아야 할 상품 아이디 — 등장 순서 · 중복 없이, 상한까지 */
export function previewProductIds(sections: PreviewSection[]): string[] {
  const ids = new Set<string>();
  for (const section of sections) {
    for (const item of section.items) {
      if (item.itemType === 'PRODUCT') ids.add(item.productId);
    }
  }
  return [...ids].slice(0, PREVIEW_LIMITS.products);
}

const toThumbnail = ({ imageId, url, width, height, altText }: ProductThumbnail) => ({
  imageId,
  url,
  width,
  height,
  altText,
});

/**
 * 공개 상품 상세 → 목록 카드(`ProductListItem`) 근사. 메인 전시 응답의 카드는 BE 가 목록과 같은 계산으로 주지만,
 * 미리보기 API(BE #340)가 생기기 전에는 상세로 만든다 — 실제 메인과 다를 수 있는 곳:
 * - 판매가: 판매중 SKU 의 서버 판매가 중 가장 낮은 값(없으면 전체 SKU, 그것도 없으면 기본 판매가)을 고른다. 금액을 계산하지 않고 고르기만 한다
 * - 정가 · 할인율: 상세 값(기본 판매가 기준)이라, 고른 판매가가 기본 판매가와 다르면 싣지 않는다(서로 맞지 않는 취소선 · 할인율을 그리지 않게)
 * - hover 사진: 대표 다음의 첫 정지 이미지(대표가 없으면 갤러리 두 번째 정지 이미지)
 */
export function productCardOf(detail: ProductDetail): ProductListItem {
  const onSale = detail.skus.filter((sku) => sku.saleStatus.code === 'ON_SALE');
  const pool = onSale.length > 0 ? onSale : detail.skus;
  const lowest = pool.reduce<(typeof pool)[number] | null>(
    (min, sku) => (min === null || sku.salePrice.amount < min.salePrice.amount ? sku : min),
    null,
  );
  const salePrice = lowest?.salePrice ?? detail.salePrice;
  const sameAsBase =
    salePrice.amount === detail.salePrice.amount &&
    salePrice.currency === detail.salePrice.currency;
  const stills = [
    ...(detail.thumbnail ? [detail.thumbnail] : []),
    ...detail.images.filter((image) => image.mediaType === 'IMAGE'),
  ];
  return {
    productId: detail.productId,
    name: detail.name,
    thumbnail: detail.thumbnail ? toThumbnail(detail.thumbnail) : null,
    hoverImage: stills[1] ? toThumbnail(stills[1]) : null,
    salePrice,
    listPrice: sameAsBase ? detail.listPrice : null,
    discountRate: sameAsBase ? detail.discountRate : null,
    hasOptionSurcharge: pool.some((sku) => sku.salePrice.amount > salePrice.amount),
    saleStatus: detail.saleStatus,
    customOrder: detail.customOrder !== null,
    preorder: detail.preorder !== null,
  };
}

/**
 * 노출 중인 미리보기 섹션 → 고객 메인 전시 응답 모양. 카드를 받지 못한 상품(숨김 · 삭제 — 공개 상세가 404)은 빠진다 —
 * 메인 응답이 숨긴 상품 아이템을 빼는 것과 같다. 유형 이름은 화면이 쓰지 않아 비워 둔다.
 */
export function toPreviewResponse(
  sections: PreviewSection[],
  cards: ReadonlyMap<string, ProductListItem>,
): DisplayMainResponse {
  return {
    sections: sections.map((section): DisplaySectionItem => ({
      sectionId: `preview-${section.sectionId}`,
      sectionType: { code: section.sectionType, name: '', description: '' },
      title: section.title,
      subtitle: section.subtitle,
      body: section.body,
      link: section.linkUrl ? { url: section.linkUrl, label: section.linkLabel } : null,
      items: section.items.flatMap((item, index): DisplayElementItem[] => {
        const itemId = `${section.sectionId}-${index}`;
        if (item.itemType === 'MEDIA') {
          return [
            { itemId, itemType: 'MEDIA', media: item.media, product: null, caption: item.caption },
          ];
        }
        const card = cards.get(item.productId);
        return card
          ? [{ itemId, itemType: 'PRODUCT', media: null, product: card, caption: item.caption }]
          : [];
      }),
    })),
  };
}

/** origin 값(env) → 정규화한 origin. 비었거나 http(s) origin 이 아니면 null */
export function parseOrigin(value: string | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.origin : null;
  } catch {
    return null;
  }
}

/** 앱 안 경로(`/[locale]/preview/...`)가 미리보기인가 */
const PREVIEW_PATH = /^\/[^/]+\/preview(?:\/|$)/;

/**
 * 미리보기 경로에 붙일 `Content-Security-Policy` — 어드민 origin 만 iframe 으로 열 수 있다(`frame-ancestors`).
 * 어드민 origin 이 없으면 아무도 열 수 없다(`'none'`). 미리보기가 아닌 경로면 null(지금 헤더를 바꾸지 않는다).
 */
export function previewFramePolicy(appPathname: string, admin: string | null): string | null {
  if (!PREVIEW_PATH.test(appPathname)) return null;
  return `frame-ancestors ${admin ?? "'none'"}`;
}
