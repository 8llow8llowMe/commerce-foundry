import type { FormErrors } from '@/lib/api-errors';
import {
  hasRejectedCharacter,
  isAllowedLinkUrl,
  isoToLocalInput,
  localInputToIso,
  normalizeText,
  titleWidth,
} from './text';
import {
  CAPTION_TYPES,
  ITEM_RANGES,
  LIMITS,
  LOOK_REFERENCE_TYPES,
  MEDIA_URL_TYPES,
  MOBILE_MEDIA_TYPES,
  TITLE_WIDTH,
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
 * 미디어 한 개. 이미지는 주소(`url`)와 대체 텍스트를 고친다(`MEDIA_URL_TYPES`). 영상은 주소를 바꾸지 않고 빼기만 한다.
 * PC · 모바일을 따로 넣는 유형(`MOBILE_MEDIA_TYPES`, HERO)은 모바일 주소 칸도 고친다. 그 밖의 유형은 PC 주소를 바꾸면
 * 모바일 주소를 버린다(예전 사진의 것이다).
 */
export type MediaFormItem = {
  media: DisplayMediaAdmin;
  /** 이미지 주소 칸(글자 그대로). 처음에는 `media.pcUrl` 이다 */
  url: string;
  /** 모바일 이미지 주소 칸 — 비우면 메인이 PC 사진을 쓴다. 처음에는 `media.mobileUrl` 이다 */
  mobileUrl: string;
  altText: string;
  /** 설명 문구 칸 — 설명을 쓰는 유형(`CAPTION_TYPES`)만 보이고, 그 밖은 저장된 값을 그대로 다시 보낸다 */
  caption: string;
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
    mobileUrl: '',
    altText: '',
    caption: '',
  };
}

export const isMediaUrlType = (type: SectionTypeCode) => MEDIA_URL_TYPES.includes(type);
export const hasMobileMedia = (type: SectionTypeCode) => MOBILE_MEDIA_TYPES.includes(type);
export const hasMediaCaption = (type: SectionTypeCode) => CAPTION_TYPES.includes(type);
export const isLookReferenceType = (type: SectionTypeCode) => LOOK_REFERENCE_TYPES.includes(type);

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
  /** 고른 룩 아이디(룩 섹션, BE #372) — 배열 순서가 메인 노출 순서다. 이름 · 노출 여부는 룩 목록으로 보인다(`LookPickField`) */
  lookIds: string[];
  /**
   * 이관 전 룩 섹션(미디어 · 상품으로 저장된 LOOK)의 미디어 · 상품 수. 고객 메인에 보이지 않고(BE `isLegacyLook`),
   * 룩을 골라 저장하면 지워진다(`backend/docs/display-look-migration.md`). 0 이면 이관 전 섹션이 아니다
   */
  legacyItemCount: number;
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
export const TOO_LONG: Record<TextFieldKey | 'altText' | 'mediaUrl' | 'caption', string> = {
  title: `제목은 ${LIMITS.title}자 이하로 써 주세요.`,
  subtitle: `부제는 ${LIMITS.subtitle}자 이하로 써 주세요.`,
  body: `본문은 ${LIMITS.body}자 이하로 써 주세요.`,
  linkUrl: `링크 주소는 ${LIMITS.linkUrl}자 이하로 넣어 주세요.`,
  linkLabel: `링크 버튼 문구는 ${LIMITS.linkLabel}자 이하로 써 주세요.`,
  altText: `대체 텍스트는 ${LIMITS.altText}자 이하로 써 주세요.`,
  mediaUrl: `이미지 주소는 ${LIMITS.mediaUrl}자 이하로 넣어 주세요.`,
  caption: `설명은 ${LIMITS.caption}자 이하로 써 주세요.`,
};

/** 제목 너비 한도 안내(2줄 규칙) — 한도는 유형마다 다르다(`TITLE_WIDTH`) */
export function titleTooWide(type: SectionTypeCode): string {
  return `제목이 길어 메인에서 2줄을 넘을 수 있습니다. 한글 기준 ${TITLE_WIDTH[type]}자(영문 · 숫자 · 공백은 반 글자) 이내로 줄여 주세요.`;
}

export const SINGLE_LINE_ONLY = '줄바꿈 · 탭 같은 특수 문자는 쓸 수 없습니다.';
export const BODY_CHARACTER = '본문에는 줄바꿈과 탭 외의 특수 문자를 쓸 수 없습니다.';
export const LINK_RULE =
  '링크 주소는 / 로 시작하는 쇼핑몰 안 주소(예: /shop) 또는 https:// 로 시작하는 주소만 넣을 수 있습니다.';
export const LABEL_WITHOUT_URL = '링크 버튼 문구를 쓰려면 링크 주소도 넣어 주세요.';
export const PERIOD_FORMAT = '날짜와 시각을 0001년 ~ 9999년 사이로 넣어 주세요.';
export const PERIOD_ORDER = '노출 종료는 노출 시작보다 뒤여야 합니다.';
export const PRODUCT_DUPLICATED = '같은 상품이 두 번 들어 있습니다.';
export const MEDIA_URL_REQUIRED = '이미지 주소를 넣어 주세요.';
export const ALT_TEXT_REQUIRED =
  '대체 텍스트를 써 주세요. 사진을 볼 수 없는 고객에게 읽어 주는 문장입니다.';
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

export const LOOK_DUPLICATED = '같은 룩이 두 번 들어 있습니다.';
export const LOOK_MISSING =
  '찾을 수 없는(지워진) 룩이 있습니다. 그 룩을 빼거나 다른 룩으로 바꿔 저장해 주세요.';

/** 룩 수 범위 안내 — 하한 · 상한은 BE 섹션 규칙 */
export function lookCountMessage(type: SectionTypeCode): string {
  const [min, max] = ITEM_RANGES[type].look;
  return `룩은 ${min}개 이상 ${max}개 이하로 골라 주세요.`;
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
  const lookIds: string[] = [];
  let legacyItemCount = 0;
  const lookType = isLookReferenceType(type);
  for (const item of section.items) {
    if (item.lookId) {
      lookIds.push(item.lookId);
    } else if (lookType) {
      // 이관 전 룩의 미디어 · 상품 — 폼에 싣지 않는다(룩 섹션은 받지 않는다). 저장하면 룩 참조로 바뀐다
      legacyItemCount++;
    } else if (item.media) {
      media.push({
        media: item.media,
        url: item.media.pcUrl,
        mobileUrl: item.media.mobileUrl ?? '',
        altText: item.media.altText ?? '',
        caption: item.caption ?? '',
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
    lookIds,
    legacyItemCount,
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
    lookIds: [],
    legacyItemCount: 0,
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
 * - 아이템 순서는 미디어 → 상품 → 룩이다. 한 유형이 두 종류를 받지 않으므로 순서가 섞이지 않는다.
 * - 룩 섹션은 룩 참조만 보낸다(BE #372) — 수(1~10)와 중복을 본다. 지워진 룩인지는 서버가 본다(`DISPLAY_001`).
 * - 제목은 BE 200자에 더해 유형별 너비 한도(`TITLE_WIDTH`, 2줄 규칙)를 넘으면 막는다 — 서버는 보지 않는 화면 규칙이다(#71).
 * - 미디어는 이미지 수(BE 하한 · 상한)와 주소(필수 · 500자 · 공백 금지), 모바일 주소(선택, 같은 규칙), 설명(200자 · 한 줄)을 본다.
 *   저장소 주소인지는 서버가 본다(`PRODUCT_054`).
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
    else if (key === 'title' && titleWidth(value) > TITLE_WIDTH[form.sectionType]) {
      push(errors, key, titleTooWide(form.sectionType));
    }
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
  const mobileEditable = hasMobileMedia(form.sectionType);
  const captionEditable = hasMediaCaption(form.sectionType);
  const checkUrl = (value: string, key: string) => {
    if (value.length > LIMITS.mediaUrl) push(errors, key, TOO_LONG.mediaUrl);
    if (MEDIA_URL_REJECTED.test(value)) push(errors, key, MEDIA_URL_FORMAT);
  };
  form.media.forEach((item, index) => {
    const isImage = item.media.mediaType.code === 'IMAGE';
    if (urlEditable && isImage) {
      const url = normalizeText(item.url);
      const key = `media.${index}.url`;
      if (url === null) push(errors, key, MEDIA_URL_REQUIRED);
      else checkUrl(url, key);
      const mobileUrl = mobileEditable ? normalizeText(item.mobileUrl) : null;
      if (mobileUrl !== null) checkUrl(mobileUrl, `media.${index}.mobileUrl`);
    }
    const caption = captionEditable ? normalizeText(item.caption) : null;
    if (caption !== null) {
      const key = `media.${index}.caption`;
      if (caption.length > LIMITS.caption) push(errors, key, TOO_LONG.caption);
      if (hasRejectedCharacter(caption)) push(errors, key, SINGLE_LINE_ONLY);
    }
    const alt = normalizeText(item.altText);
    if (alt === null) {
      // 이미지는 대체 텍스트가 있어야 저장한다(#71 — BE 는 선택값). 영상은 메인이 낭독하지 않는다(aria-hidden)
      if (isImage) push(errors, `media.${index}.altText`, ALT_TEXT_REQUIRED);
      return;
    }
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

  const [minLook, maxLook] = ITEM_RANGES[form.sectionType].look;
  if (maxLook > 0 && (form.lookIds.length < minLook || form.lookIds.length > maxLook)) {
    push(errors, 'looks', lookCountMessage(form.sectionType));
  }
  if (new Set(form.lookIds).size !== form.lookIds.length) push(errors, 'looks', LOOK_DUPLICATED);

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
        ...form.media.map((item) => formMediaRequest(item, form.sectionType)),
        ...form.products.map((item): DisplayItemRequest => ({
          itemType: 'PRODUCT',
          productId: item.productId,
          caption: item.caption,
        })),
        ...form.lookIds.map(lookRequest),
      ],
    },
  };
}

/**
 * 폼의 미디어 → 요청. 영상은 주소를 그대로 보낸다. 이미지는 고친 주소를 싣는다 — 모바일 칸이 있는 유형은 그 칸 값을,
 * 없는 유형은 PC 주소를 바꿨으면 모바일 주소를 버린다(예전 사진의 것이다).
 */
function formMediaRequest(item: MediaFormItem, type: SectionTypeCode): DisplayItemRequest {
  let media = item.media;
  if (media.mediaType.code === 'IMAGE') {
    const url = normalizeText(item.url) ?? '';
    const mobileUrl = hasMobileMedia(type)
      ? normalizeText(item.mobileUrl)
      : url === media.pcUrl
        ? media.mobileUrl
        : null;
    media = { ...media, pcUrl: url, mobileUrl };
  }
  return mediaRequest(media, normalizeText(item.altText), normalizeText(item.caption));
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
      ...form.media
        .filter((item) => normalizeText(item.url) !== null)
        .map((item) => formMediaRequest(item, form.sectionType)),
      ...form.products.map((item): DisplayItemRequest => ({
        itemType: 'PRODUCT',
        productId: item.productId,
        caption: item.caption,
      })),
      ...form.lookIds.map(lookRequest),
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

const lookRequest = (lookId: string): DisplayItemRequest => ({
  itemType: 'LOOK',
  lookId,
  caption: null,
});

function itemRequest(item: DisplayElementAdmin): DisplayItemRequest | null {
  if (item.lookId) return { itemType: 'LOOK', lookId: item.lookId, caption: item.caption };
  if (item.media) return mediaRequest(item.media, item.media.altText, item.caption);
  if (item.product) {
    return { itemType: 'PRODUCT', productId: item.product.productId, caption: item.caption };
  }
  return null;
}

/**
 * 저장된 섹션을 그대로 다시 보내는 본문 — 목록의 노출 켜기 · 끄기(전체 교체 PUT 이라 나머지 칸 · 아이템을 모두 싣는다).
 * 아이템은 응답 순서 그대로다. 이관 전 룩 섹션은 미디어 · 상품 그대로 보내 BE 가 거절한다(`PRODUCT_047`, `rowFailure`).
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
  if (form.legacyItemCount > 0) {
    warnings.push(
      `이관 전 룩 섹션입니다(이미지 · 상품 ${form.legacyItemCount}개). 지금은 메인에 보이지 않습니다. 룩을 골라 저장하면 기존 이미지 · 상품은 이 섹션에서 지워지니, 필요하면 룩 관리에서 먼저 룩으로 만들어 두세요.`,
    );
  }
  return warnings;
}
