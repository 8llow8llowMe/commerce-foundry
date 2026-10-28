import type { Currency } from '@cf/api-client';
import type { SaleStatusCode } from '@/lib/catalog/types';
import type {
  DisplayElementItem,
  DisplayMainResponse,
  DisplayMediaItem,
  DisplayProductItem,
  DisplayProductThumbnailItem,
  DisplaySectionItem,
} from './types';

// 어드민 메인 전시 미리보기(#71). 어드민이 저장 전 편집 상태로 BE 미리보기(`POST /api/v1/admin/displays/preview`, #340)를 부르고,
// 받은 응답(고객 메인 응답과 같은 모양)을 iframe 으로 연 `/preview/main` 에 postMessage 로 넘긴다. 스토어프론트는 메인과 같은 경로로 그리기만 한다.
// 메시지 모양의 정본은 이 파일이다 — 어드민 `lib/display/preview.ts` 가 같은 모양을 보낸다. 규칙은 frontend/docs/architecture.md §10.

/** iframe → 어드민: 받을 준비가 됐다(어드민은 이것을 받으면 마지막으로 받은 미리보기 응답을 보낸다) */
export const PREVIEW_READY = 'cf:main-preview:ready';
/** 어드민 → iframe: BE 미리보기 응답(`display`, 고객 메인 응답과 같은 모양) */
export const PREVIEW_DISPLAY = 'cf:main-preview:display';

/**
 * 받은 메시지가 부모 창(어드민)이 보낸 미리보기 응답인가 — 허용한 어드민 origin 과 부모 창이 보낸 것만 믿는다.
 * 다른 창 · 다른 origin 이 보낸 같은 모양의 메시지는 그리지 않는다.
 */
export function isPreviewDisplay(
  event: Pick<MessageEvent, 'origin' | 'source' | 'data'>,
  adminOrigin: string,
  parent: MessageEventSource,
): event is Pick<MessageEvent, 'origin' | 'source'> & {
  data: { type: typeof PREVIEW_DISPLAY; display: unknown };
} {
  if (event.origin !== adminOrigin || event.source !== parent) return false;
  const data: unknown = event.data;
  return isRecord(data) && data.type === PREVIEW_DISPLAY && 'display' in data;
}

/**
 * 서버 액션이 읽는 상한 — BE 상한(섹션 50 · 섹션당 아이템 30)과 같다. 서버 액션은 인증 없이 누구나 부를 수 있어
 * 응답 모양을 다시 검사한다(어드민이 넘긴 BE 응답이 아닐 수 있다). 넘는 것은 버린다
 */
export const PREVIEW_LIMITS = { sections: 50, items: 30 } as const;

/** 상품 아이디는 상품 링크 경로에 들어가므로 숫자만 받는다(Long) */
const PRODUCT_ID = /^[1-9]\d{0,18}$/;
/** 섹션 아이디는 블록 id 에 들어간다 — BE 임시 아이디(`preview-section-0`) 모양 */
const SECTION_ID = /^[\w-]{1,64}$/;
const CURRENCIES: readonly Currency[] = ['KRW', 'USD'];

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() !== '' ? value : null;
const size = (value: unknown): number | null =>
  Number.isSafeInteger(value) && (value as number) > 0 ? (value as number) : null;

/** 미디어 · 상품 사진 주소는 http(s) 만 그린다 — `<img>` 로만 쓰고 서버가 그 주소를 가져가지 않는다 */
function mediaUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? value : null;
  } catch {
    return null;
  }
}

function readMoney(value: unknown): DisplayProductItem['salePrice'] | null {
  if (!isRecord(value)) return null;
  const { amount, currency } = value;
  if (!Number.isSafeInteger(amount) || (amount as number) < 0) return null;
  if (!CURRENCIES.includes(currency as Currency)) return null;
  return { amount: amount as number, currency: currency as Currency };
}

function readThumbnail(value: unknown): DisplayProductThumbnailItem | null {
  if (!isRecord(value) || typeof value.imageId !== 'string') return null;
  const url = mediaUrl(value.url);
  if (!url) return null;
  return {
    imageId: value.imageId,
    url,
    width: size(value.width),
    height: size(value.height),
    altText: text(value.altText),
  };
}

/** 상품 카드 — 목록 항목과 같은 모양. 금액 · 판매 상태가 맞지 않으면 그 상품을 버린다(그리다 깨지지 않게) */
function readProduct(value: unknown): DisplayProductItem | null {
  if (!isRecord(value)) return null;
  const { productId, name, saleStatus, discountRate } = value;
  if (typeof productId !== 'string' || !PRODUCT_ID.test(productId)) return null;
  if (typeof name !== 'string') return null;
  const salePrice = readMoney(value.salePrice);
  if (!salePrice) return null;
  if (!isRecord(saleStatus) || typeof saleStatus.code !== 'string') return null;
  if (typeof saleStatus.name !== 'string') return null;
  const listPrice = readMoney(value.listPrice);
  const rate =
    Number.isSafeInteger(discountRate) &&
    (discountRate as number) > 0 &&
    (discountRate as number) < 100
      ? (discountRate as number)
      : null;
  return {
    productId,
    name,
    thumbnail: readThumbnail(value.thumbnail),
    hoverImage: readThumbnail(value.hoverImage),
    salePrice,
    // 정가 · 할인율은 함께 있거나 함께 없다(BE 계약)
    listPrice: listPrice && rate !== null ? listPrice : null,
    discountRate: listPrice && rate !== null ? rate : null,
    hasOptionSurcharge: value.hasOptionSurcharge === true,
    saleStatus: {
      code: saleStatus.code as SaleStatusCode,
      name: saleStatus.name,
      description: typeof saleStatus.description === 'string' ? saleStatus.description : '',
    },
    customOrder: value.customOrder === true,
    preorder: value.preorder === true,
  };
}

function readMedia(value: unknown): DisplayMediaItem | null {
  if (!isRecord(value) || typeof value.mediaType !== 'string') return null;
  const pcUrl = mediaUrl(value.pcUrl);
  if (!pcUrl) return null;
  return {
    mediaType: value.mediaType,
    pcUrl,
    mobileUrl: mediaUrl(value.mobileUrl),
    pcPosterUrl: mediaUrl(value.pcPosterUrl),
    mobilePosterUrl: mediaUrl(value.mobilePosterUrl),
    altText: text(value.altText),
  };
}

function readItem(value: unknown, index: number): DisplayElementItem | null {
  if (!isRecord(value)) return null;
  // 아이템 아이디는 화면이 쓰지 않는다 — BE 임시 아이디를 믿지 않고 위치로 다시 붙인다
  const itemId = `preview-item-${index}`;
  const caption = text(value.caption);
  if (value.itemType === 'MEDIA') {
    const media = readMedia(value.media);
    return media ? { itemId, itemType: 'MEDIA', media, product: null, caption } : null;
  }
  if (value.itemType === 'PRODUCT') {
    const product = readProduct(value.product);
    return product ? { itemId, itemType: 'PRODUCT', media: null, product, caption } : null;
  }
  return null;
}

function readSection(value: unknown): DisplaySectionItem | null {
  if (!isRecord(value)) return null;
  const { sectionId, sectionType, items, link } = value;
  if (typeof sectionId !== 'string' || !SECTION_ID.test(sectionId)) return null;
  if (!isRecord(sectionType) || typeof sectionType.code !== 'string') return null;
  if (!Array.isArray(items)) return null;
  return {
    sectionId,
    sectionType: { code: sectionType.code, name: '', description: '' },
    title: text(value.title),
    subtitle: text(value.subtitle),
    body: text(value.body),
    // 주소 규칙(`safeHref`)은 메인과 같이 그릴 때 본다
    link:
      isRecord(link) && typeof link.url === 'string'
        ? { url: link.url, label: text(link.label) }
        : null,
    items: items
      .slice(0, PREVIEW_LIMITS.items)
      .map(readItem)
      .filter((item) => item !== null),
  };
}

/**
 * 받은 미리보기 응답 → 고객 메인 응답 모양(`DisplayMainResponse`). 모양이 다르면 null, 모양이 다른 섹션 · 아이템 하나는 버린다.
 * 노출 판정(노출 여부 · 기간 · 숨긴 상품)은 BE 가 이미 했다 — 메인처럼 다시 거르지 않는다.
 * 문구는 평문으로만 그리고(텍스트 노드) 링크는 메인과 같이 `safeHref` 를 거친다.
 */
export function readPreviewDisplay(value: unknown): DisplayMainResponse | null {
  if (!isRecord(value) || !Array.isArray(value.sections)) return null;
  return {
    sections: value.sections
      .slice(0, PREVIEW_LIMITS.sections)
      .map(readSection)
      .filter((section) => section !== null),
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
