import type { FormErrors } from '@/lib/api-errors';
import {
  hasRejectedCharacter,
  isAllowedLinkUrl,
  isoToLocalInput,
  localInputToIso,
  normalizeText,
} from './text';
import {
  ITEM_RANGES,
  LIMITS,
  MEDIA_URL_TYPES,
  type CodeName,
  type DisplayElementAdmin,
  type DisplayItemRequest,
  type DisplayMediaAdmin,
  type DisplaySectionAdmin,
  type DisplaySectionRequest,
  type SectionTypeCode,
} from './types';

/** 섹션 문구 칸 */
export type TextFieldKey = 'title' | 'subtitle' | 'body' | 'linkUrl' | 'linkLabel';

/**
 * 유형별로 폼에 보이는 문구 칸 — 스토어프론트 메인이 실제로 그리는 칸만 둔다(`frontend/docs/architecture.md` §10).
 * 보이지 않는 칸의 저장된 값은 그대로 다시 보낸다(전체 교체 PUT 이라 빼면 지워진다).
 */
export const FIELDS_BY_TYPE: Record<SectionTypeCode, readonly TextFieldKey[]> = {
  HERO: ['title', 'subtitle', 'linkUrl', 'linkLabel'],
  PRODUCT_PICK: ['title', 'linkUrl', 'linkLabel'],
  STORY: ['title', 'body', 'linkUrl', 'linkLabel'],
  DETAIL: ['title', 'body'],
  LOOK: ['title', 'body', 'linkUrl', 'linkLabel'],
};

/**
 * 미디어 한 개. 대부분의 유형은 주소를 그대로 다시 보내고 대체 텍스트만 고친다(교체는 업로드 기능 #9 뒤).
 * 주소로 넣는 유형(`MEDIA_URL_TYPES`, LOOK)은 이미지 주소(`url`)도 고친다 — 바꾸면 모바일 주소는 버린다(예전 사진의 것이다).
 */
export type MediaFormItem = {
  media: DisplayMediaAdmin;
  /** 이미지 주소 칸(글자 그대로). 처음에는 `media.pcUrl` 이다 */
  url: string;
  altText: string;
  caption: string | null;
};

/** 주소로 넣는 새 이미지 — 주소 칸이 빈 채로 시작한다 */
export function newImageItem(): MediaFormItem {
  return {
    media: {
      mediaType: { code: 'IMAGE', name: '이미지', description: '' },
      pcUrl: '',
      mobileUrl: null,
      pcPosterUrl: null,
      mobilePosterUrl: null,
      altText: null,
    },
    url: '',
    altText: '',
    caption: null,
  };
}

export const isMediaUrlType = (type: SectionTypeCode) => MEDIA_URL_TYPES.includes(type);

/** 고른 상품. availability 는 이번에 검색해서 더한 상품만 안다(섹션 응답에는 노출 여부만 있다) */
export type ProductFormItem = {
  productId: string;
  /** null 이면 상품을 찾을 수 없다(지워진 상품) */
  name: string | null;
  visible: boolean;
  availability: CodeName | null;
  caption: string | null;
};

/** 폼 칸은 글자 그대로 둔다. 기간은 datetime-local 글자(이 컴퓨터 시간대) */
export type SectionForm = Record<TextFieldKey, string> & {
  sectionType: SectionTypeCode;
  visible: boolean;
  startAt: string;
  endAt: string;
  media: MediaFormItem[];
  products: ProductFormItem[];
  /** 불러온 기간(UTC ISO) — 칸을 고치지 않았으면 이 값을 그대로 보낸다(분 아래 초 · 밀리초를 잃지 않게) */
  loadedPeriod: { startAt: string | null; endAt: string | null };
};

export const TEXT_LABELS: Record<TextFieldKey, string> = {
  title: '제목',
  subtitle: '부제',
  body: '본문',
  linkUrl: '링크 주소',
  linkLabel: '링크 버튼 문구',
};

/** 길이 안내 — 칸마다 조사가 달라 문장을 통째로 둔다 */
export const TOO_LONG: Record<TextFieldKey | 'altText' | 'mediaUrl', string> = {
  title: `제목은 ${LIMITS.title}자 이하로 써 주세요.`,
  subtitle: `부제는 ${LIMITS.subtitle}자 이하로 써 주세요.`,
  body: `본문은 ${LIMITS.body}자 이하로 써 주세요.`,
  linkUrl: `링크 주소는 ${LIMITS.linkUrl}자 이하로 넣어 주세요.`,
  linkLabel: `링크 버튼 문구는 ${LIMITS.linkLabel}자 이하로 써 주세요.`,
  altText: `대체 텍스트는 ${LIMITS.altText}자 이하로 써 주세요.`,
  mediaUrl: `이미지 주소는 ${LIMITS.mediaUrl}자 이하로 넣어 주세요.`,
};

export const SINGLE_LINE_ONLY = '줄바꿈 · 탭 같은 특수 문자는 쓸 수 없습니다.';
export const BODY_CHARACTER = '본문에는 줄바꿈과 탭 외의 특수 문자를 쓸 수 없습니다.';
export const LINK_RULE =
  '링크 주소는 / 로 시작하는 쇼핑몰 안 주소(예: /shop) 또는 https:// 로 시작하는 주소만 넣을 수 있습니다.';
export const LABEL_WITHOUT_URL = '링크 버튼 문구를 쓰려면 링크 주소도 넣어 주세요.';
export const PERIOD_FORMAT = '날짜와 시각을 0001년 ~ 9999년 사이로 넣어 주세요.';
export const PERIOD_ORDER = '노출 종료는 노출 시작보다 뒤여야 합니다.';
export const PRODUCT_DUPLICATED = '같은 상품이 두 번 들어 있습니다.';
export const MEDIA_URL_REQUIRED = '이미지 주소를 넣어 주세요.';
export const MEDIA_URL_FORMAT = '이미지 주소에는 공백 · 줄바꿈을 넣을 수 없습니다.';
export const MEDIA_URL_NOT_STORAGE =
  '이미지 주소는 쇼핑몰 이미지 저장소에 올린 이미지의 주소만 넣을 수 있습니다. 다른 사이트의 주소인지 확인해 주세요.';

/** 상품 수 범위 안내 — 하한 · 상한은 BE 섹션 규칙 */
export function productCountMessage(type: SectionTypeCode): string {
  const [min, max] = ITEM_RANGES[type].product;
  return min > 0
    ? `상품은 ${min}개 이상 ${max}개 이하로 넣어 주세요.`
    : `상품은 ${max}개까지 넣을 수 있습니다.`;
}

/** 이미지 · 영상 수 범위 안내 — 하한 · 상한은 BE 섹션 규칙 */
export function mediaCountMessage(type: SectionTypeCode): string {
  const [min, max] = ITEM_RANGES[type].media;
  return `이미지 · 영상은 ${min}개 이상 ${max}개 이하로 넣어 주세요.`;
}

// 이미지 주소 — 공백 · 제어 문자 · 짝 없는 서로게이트는 어디에도 쓸 수 없다(룩 이미지 주소와 같다, BE #373)
const MEDIA_URL_REJECTED = /[\s\p{Zs}\p{Cc}\p{Cs}]/u;

/** 저장된 섹션 → 폼 */
export function editForm(section: DisplaySectionAdmin, type: SectionTypeCode): SectionForm {
  const media: MediaFormItem[] = [];
  const products: ProductFormItem[] = [];
  for (const item of section.items) {
    if (item.media) {
      media.push({
        media: item.media,
        url: item.media.pcUrl,
        altText: item.media.altText ?? '',
        caption: item.caption,
      });
    } else if (item.product) {
      products.push({
        productId: item.product.productId,
        name: item.product.name,
        visible: item.product.visible,
        availability: null,
        caption: item.caption,
      });
    }
  }
  return {
    sectionType: type,
    title: section.title ?? '',
    subtitle: section.subtitle ?? '',
    body: section.body ?? '',
    linkUrl: section.linkUrl ?? '',
    linkLabel: section.linkLabel ?? '',
    visible: section.visible,
    startAt: isoToLocalInput(section.startAt),
    endAt: isoToLocalInput(section.endAt),
    media,
    products,
    loadedPeriod: { startAt: section.startAt, endAt: section.endAt },
  };
}

/**
 * 새 섹션 폼 — 처음에는 숨김으로 둔다(내용을 채우기 전에 고객 메인에 나가지 않게).
 * 미디어를 주소로 넣는 유형은 하한만큼 빈 이미지 칸을 두고 시작한다.
 */
export function createForm(type: SectionTypeCode): SectionForm {
  const mediaSlots = isMediaUrlType(type) ? ITEM_RANGES[type].media[0] : 0;
  return {
    sectionType: type,
    title: '',
    subtitle: '',
    body: '',
    linkUrl: '',
    linkLabel: '',
    visible: false,
    startAt: '',
    endAt: '',
    media: Array.from({ length: mediaSlots }, newImageItem),
    products: [],
    loadedPeriod: { startAt: null, endAt: null },
  };
}

export type BuildResult =
  { ok: true; request: DisplaySectionRequest } | { ok: false; errors: FormErrors };

const push = (errors: FormErrors, key: string, message: string) => {
  errors[key] = [...(errors[key] ?? []), message];
};

/** 기간 칸 하나 → 보낼 값. 고치지 않았으면 불러온 값을 그대로 쓴다. undefined 면 형식 오류 */
function periodValue(text: string, loaded: string | null): string | null | undefined {
  if (text === '') return null;
  if (loaded !== null && text === isoToLocalInput(loaded)) return loaded;
  return localInputToIso(text) ?? undefined;
}

/**
 * 폼 → 생성 · 수정(전체 교체) 본문. 규칙을 어기면 보내지 않고 칸 오류를 돌려준다 — 판정은 BE 와 같다.
 * - 문구는 앞뒤 공백을 빼고 비면 null 이다. 길이는 UTF-16 코드 유닛(BE `@Size` 와 같다).
 * - 보이는 칸만 검사한다 — 보이지 않는 칸은 저장된 값을 그대로 다시 보낸다(서버가 이미 받은 값이다).
 * - 아이템 순서는 미디어 → 상품이다. LOOK 만 두 종류를 받는데, 메인은 미디어 · 상품을 따로 읽으므로 섞인 저장 순서를 이 순서로 바꿔도 같다.
 * - 주소로 넣는 유형(LOOK)은 이미지 수(BE 하한 · 상한)와 주소(필수 · 500자 · 공백 금지)를 본다. 저장소 주소인지는 서버가 본다(`PRODUCT_054`).
 */
export function sectionRequest(form: SectionForm): BuildResult {
  const errors: FormErrors = {};
  const shown = new Set(FIELDS_BY_TYPE[form.sectionType]);
  const text = {} as Record<TextFieldKey, string | null>;
  for (const key of Object.keys(TEXT_LABELS) as TextFieldKey[]) {
    const value = normalizeText(form[key]);
    text[key] = value;
    if (!shown.has(key) || value === null) continue;
    if (value.length > LIMITS[key]) push(errors, key, TOO_LONG[key]);
    if (key === 'linkUrl') {
      if (!isAllowedLinkUrl(value)) push(errors, key, LINK_RULE);
    } else if (hasRejectedCharacter(value, key === 'body')) {
      push(errors, key, key === 'body' ? BODY_CHARACTER : SINGLE_LINE_ONLY);
    }
  }
  if (shown.has('linkLabel') && text.linkLabel !== null && text.linkUrl === null) {
    push(errors, 'linkLabel', LABEL_WITHOUT_URL);
  }

  const startAt = periodValue(form.startAt, form.loadedPeriod.startAt);
  const endAt = periodValue(form.endAt, form.loadedPeriod.endAt);
  if (startAt === undefined || endAt === undefined) push(errors, 'period', PERIOD_FORMAT);
  else if (startAt && endAt && Date.parse(endAt) <= Date.parse(startAt)) {
    push(errors, 'period', PERIOD_ORDER);
  }

  const urlEditable = isMediaUrlType(form.sectionType);
  if (urlEditable) {
    const [minMedia, maxMedia] = ITEM_RANGES[form.sectionType].media;
    if (form.media.length < minMedia || form.media.length > maxMedia) {
      push(errors, 'media', mediaCountMessage(form.sectionType));
    }
  }
  form.media.forEach((item, index) => {
    if (urlEditable) {
      const url = normalizeText(item.url);
      const key = `media.${index}.url`;
      if (url === null) push(errors, key, MEDIA_URL_REQUIRED);
      else {
        if (url.length > LIMITS.mediaUrl) push(errors, key, TOO_LONG.mediaUrl);
        if (MEDIA_URL_REJECTED.test(url)) push(errors, key, MEDIA_URL_FORMAT);
      }
    }
    const alt = normalizeText(item.altText);
    if (alt === null) return;
    if (alt.length > LIMITS.altText) push(errors, `media.${index}.altText`, TOO_LONG.altText);
    if (hasRejectedCharacter(alt)) push(errors, `media.${index}.altText`, SINGLE_LINE_ONLY);
  });

  const [min, max] = ITEM_RANGES[form.sectionType].product;
  if (max > 0 && (form.products.length < min || form.products.length > max)) {
    push(errors, 'products', productCountMessage(form.sectionType));
  }
  if (new Set(form.products.map((item) => item.productId)).size !== form.products.length) {
    push(errors, 'products', PRODUCT_DUPLICATED);
  }

  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return {
    ok: true,
    request: {
      sectionType: form.sectionType,
      ...text,
      visible: form.visible,
      startAt: startAt ?? null,
      endAt: endAt ?? null,
      items: [
        ...form.media.map(formMediaRequest),
        ...form.products.map((item): DisplayItemRequest => ({
          itemType: 'PRODUCT',
          productId: item.productId,
          caption: item.caption,
        })),
      ],
    },
  };
}

/** 폼의 미디어 → 요청. 주소를 바꿨으면 모바일 주소는 버린다(예전 사진의 것이다) */
function formMediaRequest(item: MediaFormItem): DisplayItemRequest {
  const url = normalizeText(item.url) ?? '';
  const changed = url !== item.media.pcUrl;
  return mediaRequest(
    changed ? { ...item.media, pcUrl: url, mobileUrl: null } : item.media,
    normalizeText(item.altText),
    item.caption,
  );
}

/**
 * 미리보기(#71)용 본문 — 저장 전 폼을 그대로 싣는다. 저장(`sectionRequest`)과 달리 칸 오류가 있어도 막지 않는다:
 * 문구는 앞뒤 공백만 빼고, 읽을 수 없는 기간 칸은 비운 것(제한 없음)으로 본다. 주소 칸이 빈 이미지(아직 넣는 중)는 뺀다.
 * 스토어프론트가 평문 · 안전한 링크로만 그린다.
 */
export function draftRequest(form: SectionForm): DisplaySectionRequest {
  const text = {} as Record<TextFieldKey, string | null>;
  for (const key of Object.keys(TEXT_LABELS) as TextFieldKey[])
    text[key] = normalizeText(form[key]);
  return {
    sectionType: form.sectionType,
    ...text,
    visible: form.visible,
    startAt: periodValue(form.startAt, form.loadedPeriod.startAt) ?? null,
    endAt: periodValue(form.endAt, form.loadedPeriod.endAt) ?? null,
    items: [
      ...form.media.filter((item) => normalizeText(item.url) !== null).map(formMediaRequest),
      ...form.products.map((item): DisplayItemRequest => ({
        itemType: 'PRODUCT',
        productId: item.productId,
        caption: item.caption,
      })),
    ],
  };
}

function mediaRequest(
  media: DisplayMediaAdmin,
  altText: string | null,
  caption: string | null,
): DisplayItemRequest {
  return {
    itemType: 'MEDIA',
    media: {
      mediaType: media.mediaType.code,
      pcUrl: media.pcUrl,
      mobileUrl: media.mobileUrl,
      pcPosterUrl: media.pcPosterUrl,
      mobilePosterUrl: media.mobilePosterUrl,
      altText,
    },
    caption,
  };
}

function itemRequest(item: DisplayElementAdmin): DisplayItemRequest | null {
  if (item.media) return mediaRequest(item.media, item.media.altText, item.caption);
  if (item.product) {
    return { itemType: 'PRODUCT', productId: item.product.productId, caption: item.caption };
  }
  return null;
}

/**
 * 저장된 섹션을 그대로 다시 보내는 본문 — 목록의 노출 켜기 · 끄기(전체 교체 PUT 이라 나머지 칸 · 아이템을 모두 싣는다).
 * 아이템은 응답 순서 그대로다(LOOK 은 미디어 · 상품이 섞여 있다).
 */
export function unchangedRequest(
  section: DisplaySectionAdmin,
  patch: Partial<Pick<DisplaySectionRequest, 'visible'>>,
): DisplaySectionRequest {
  return {
    sectionType: section.sectionType.code,
    title: section.title,
    subtitle: section.subtitle,
    body: section.body,
    linkUrl: section.linkUrl,
    linkLabel: section.linkLabel,
    visible: section.visible,
    startAt: section.startAt,
    endAt: section.endAt,
    items: section.items.map(itemRequest).filter((item) => item !== null),
    ...patch,
  };
}

/**
 * 메인에 보이지 않게 되는 내용 — 저장은 되지만 운영자가 알아야 할 것(스토어프론트 `toHomeBlocks` 의 "건너뜀" 규칙).
 */
export function sectionWarnings(form: SectionForm): string[] {
  const warnings: string[] = [];
  if (
    form.sectionType === 'PRODUCT_PICK' &&
    form.products.length > 0 &&
    form.products.every((item) => !item.visible)
  ) {
    warnings.push('넣은 상품이 모두 숨김이라 이 섹션은 메인에 보이지 않습니다.');
  }
  if (
    form.sectionType === 'STORY' &&
    form.media.length === 0 &&
    normalizeText(form.title) === null &&
    normalizeText(form.body) === null
  ) {
    warnings.push('제목 · 본문이 모두 비어 있으면 이 섹션은 메인에 보이지 않습니다.');
  }
  if (
    form.sectionType === 'LOOK' &&
    form.products.length > 0 &&
    form.products.every((item) => !item.visible)
  ) {
    warnings.push('넣은 상품이 모두 숨김이라 메인에는 사진 · 문구만 보이고 함께 담기가 없습니다.');
  }
  return warnings;
}
