import type { FormErrors } from '@/lib/api-errors';
import type { ProductFormItem } from '@/lib/display/form';
import { hasRejectedCharacter, normalizeText } from '@/lib/display/text';
import { LOOK_LIMITS, type LookAdmin, type LookRequest } from './types';

/** 룩 폼 칸 — 오류 키도 같다(`image.url` 은 `imageUrl`, 구성 상품은 `products`, 폼 전체는 `form`) */
export type LookTextKey = 'title' | 'description' | 'imageUrl' | 'altText';

/** 이미지 원본 크기 — 그 주소의 크기일 때만 보낸다(주소를 바꾸면 버린다) */
export type ImageSize = { url: string; width: number; height: number };

/** 폼 칸은 글자 그대로 둔다. 구성 상품은 메인 전시 상품 픽과 같은 모양이다(`ProductPickField`) */
export type LookForm = Record<LookTextKey, string> & {
  visible: boolean;
  imageSize: ImageSize | null;
  products: ProductFormItem[];
};

export const LOOK_LABELS: Record<LookTextKey, string> = {
  title: '제목',
  description: '설명',
  imageUrl: '대표 이미지 주소',
  altText: '대표 이미지 대체 텍스트',
};

export const LOOK_TOO_LONG: Record<LookTextKey, string> = {
  title: `제목은 ${LOOK_LIMITS.title}자 이하로 써 주세요.`,
  description: `설명은 ${LOOK_LIMITS.description}자 이하로 써 주세요.`,
  imageUrl: `이미지 주소는 ${LOOK_LIMITS.imageUrl}자 이하로 넣어 주세요.`,
  altText: `대체 텍스트는 ${LOOK_LIMITS.altText}자 이하로 써 주세요.`,
};

export const TITLE_REQUIRED = '제목을 써 주세요.';
export const IMAGE_REQUIRED = '대표 이미지 주소를 넣어 주세요.';
export const IMAGE_URL_FORMAT = '이미지 주소에는 공백 · 줄바꿈을 넣을 수 없습니다.';
export const SINGLE_LINE_ONLY = '줄바꿈 · 탭 같은 특수 문자는 쓸 수 없습니다.';
export const DESCRIPTION_CHARACTER = '설명에는 줄바꿈과 탭 외의 특수 문자를 쓸 수 없습니다.';
export const PRODUCT_COUNT = `구성 상품은 1개 이상 ${LOOK_LIMITS.products}개 이하로 넣어 주세요.`;
export const PRODUCT_DUPLICATED = '같은 상품이 두 번 들어 있습니다.';

/** 목록 · 안내 문구의 룩 이름 */
export const lookName = (look: Pick<LookAdmin, 'title'>) => look.title;

/** 저장된 룩 → 폼. 판매 상태는 판매중 · 판매 종료를 상품 고르기의 판매 상태 칸에 그대로 옮긴다(안내 문구용) */
export function editLookForm(look: LookAdmin): LookForm {
  const { width, height, url } = look.image;
  return {
    title: look.title,
    description: look.description ?? '',
    imageUrl: url,
    altText: look.image.altText ?? '',
    visible: look.visible,
    imageSize: width !== null && height !== null ? { url, width, height } : null,
    products: look.products.map((product) => ({
      productId: product.productId,
      name: product.name,
      visible: product.visible,
      availability: product.saleStatus,
      caption: null,
    })),
  };
}

/** 새 룩 폼 — 처음에는 숨김으로 둔다(내용을 확인하기 전에 고객 화면에 나가지 않게) */
export function createLookForm(): LookForm {
  return {
    title: '',
    description: '',
    imageUrl: '',
    altText: '',
    visible: false,
    imageSize: null,
    products: [],
  };
}

export type LookBuildResult =
  { ok: true; request: LookRequest } | { ok: false; errors: FormErrors };

const push = (errors: FormErrors, key: string, message: string) => {
  errors[key] = [...(errors[key] ?? []), message];
};

const IMAGE_URL_REJECTED = /[\s\p{Zs}\p{Cc}\p{Cs}]/u;

/**
 * 폼 → 생성 · 수정(전체 교체) 본문. 규칙을 어기면 보내지 않고 칸 오류를 돌려준다 — 판정은 BE 요청 검증과 같다(정본은 서버).
 * - 문구는 앞뒤 공백을 빼고, 선택 칸(설명 · 대체 텍스트)은 비면 null 이다. 길이는 UTF-16 코드 유닛.
 * - 이미지 주소가 쇼핑몰 저장소 주소인지는 서버 설정을 봐야 해서 서버가 판정한다(`LOOK_005`).
 * - 원본 크기는 그 주소의 크기를 알 때만 보낸다.
 */
export function lookRequest(form: LookForm): LookBuildResult {
  const errors: FormErrors = {};
  const title = normalizeText(form.title);
  const description = normalizeText(form.description);
  const url = normalizeText(form.imageUrl);
  const altText = normalizeText(form.altText);

  if (title === null) push(errors, 'title', TITLE_REQUIRED);
  else {
    if (title.length > LOOK_LIMITS.title) push(errors, 'title', LOOK_TOO_LONG.title);
    if (hasRejectedCharacter(title)) push(errors, 'title', SINGLE_LINE_ONLY);
  }
  if (description !== null) {
    if (description.length > LOOK_LIMITS.description) {
      push(errors, 'description', LOOK_TOO_LONG.description);
    }
    if (hasRejectedCharacter(description, true)) push(errors, 'description', DESCRIPTION_CHARACTER);
  }
  if (url === null) push(errors, 'imageUrl', IMAGE_REQUIRED);
  else {
    if (url.length > LOOK_LIMITS.imageUrl) push(errors, 'imageUrl', LOOK_TOO_LONG.imageUrl);
    if (IMAGE_URL_REJECTED.test(url)) push(errors, 'imageUrl', IMAGE_URL_FORMAT);
  }
  if (altText !== null) {
    if (altText.length > LOOK_LIMITS.altText) push(errors, 'altText', LOOK_TOO_LONG.altText);
    if (hasRejectedCharacter(altText)) push(errors, 'altText', SINGLE_LINE_ONLY);
  }
  if (form.products.length < 1 || form.products.length > LOOK_LIMITS.products) {
    push(errors, 'products', PRODUCT_COUNT);
  }
  if (new Set(form.products.map((item) => item.productId)).size !== form.products.length) {
    push(errors, 'products', PRODUCT_DUPLICATED);
  }

  if (Object.keys(errors).length > 0 || title === null || url === null) {
    return { ok: false, errors };
  }
  const size = form.imageSize?.url === url ? form.imageSize : null;
  return {
    ok: true,
    request: {
      title,
      description,
      image: { url, width: size?.width ?? null, height: size?.height ?? null, altText },
      visible: form.visible,
      productIds: form.products.map((item) => item.productId),
    },
  };
}

/** 저장된 룩을 그대로 다시 보내는 본문 — 목록의 노출 켜기 · 끄기(전체 교체 PUT 이라 나머지 칸 · 구성 상품을 모두 싣는다) */
export function unchangedLookRequest(look: LookAdmin, visible: boolean): LookRequest {
  return {
    title: look.title,
    description: look.description,
    image: { ...look.image },
    visible,
    productIds: look.products.map((product) => product.productId),
  };
}

/** 고객 상품 상세에서 달라지는 점 — 저장은 되지만 운영자가 알아야 할 것(BE 고객 조회 규칙, §2-11) */
export function lookWarnings(form: LookForm): string[] {
  if (form.products.length > 0 && form.products.every((item) => !item.visible)) {
    return [
      '구성 상품이 모두 숨김이라 고객에게는 이 룩의 상품이 보이지 않고 "함께 담기" 도 없습니다.',
    ];
  }
  return [];
}

/** 목록 줄 요약 — `상품 3개 · 숨긴 상품 1개` */
export function lookSummary(look: LookAdmin): string {
  const hidden = look.products.filter((product) => !product.visible).length;
  const parts = [`상품 ${look.products.length}개`];
  if (hidden > 0) parts.push(`숨긴 상품 ${hidden}개`);
  return parts.join(' · ');
}
