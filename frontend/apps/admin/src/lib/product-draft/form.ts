import type { FormErrors } from '@/lib/api-errors';
import { addError } from '@/lib/product/form-text';
import { isLongId } from '@/lib/product/query';

// AI 상품 초안 요청(#360 · BE #359) — 폼 → `POST /api/v1/admin/product-drafts` 본문.
// 계약 정본은 `backend/docs/api-design-guide.md` §2-14, 칸 규칙 정본은 BE `ProductDraftCreateRequest` · `ProductDraftValidationMessage` 다.
// 화면은 같은 규칙으로 미리 막아 왕복을 줄일 뿐이고 판정은 서버다. **여기 적은 값만 사실이다** — AI 가 사진으로 소재 · 치수를 정하지 않는다.

export const PRODUCT_DRAFTS_PATH = '/api/v1/admin/product-drafts';
export const productDraftPath = (draftId: string) =>
  `${PRODUCT_DRAFTS_PATH}/${encodeURIComponent(draftId)}`;
export const productDraftHref = (draftId: string) =>
  `/products/drafts/${encodeURIComponent(draftId)}`;

export const NAME_HINT_MAX = 100;
export const ORIGIN_MAX = 100;
export const TONE_MAX = 200;
export const MEASUREMENTS_NOTE_MAX = 1000;
export const LIST_ITEMS_MAX = 10;
export const LIST_ITEM_MAX = 100;
/** 대표 1 + 추가 7 */
export const DRAFT_IMAGES_MAX = 8;
export const DRAFT_GALLERY_MAX = DRAFT_IMAGES_MAX - 1;
/** BE ai-core `AiImage.MAX_BYTES` — 장당 5MiB(전시 업로드 상한 10MB 보다 작다) */
export const DRAFT_IMAGE_MAX_BYTES = 5 * 1024 * 1024;

/** 목록 칸(추가 · 삭제 칩) */
export const LIST_FIELDS = [
  'materials',
  'careNotes',
  'certifications',
  'mustInclude',
  'mustAvoid',
] as const;
export type ListField = (typeof LIST_FIELDS)[number];

export const LIST_LABELS: Record<ListField, string> = {
  materials: '소재',
  careNotes: '세탁 · 관리 방법',
  certifications: '인증',
  mustInclude: '꼭 넣을 표현',
  mustAvoid: '쓰지 않을 표현',
};

export type DraftImageType = 'THUMBNAIL' | 'GALLERY';

export type ProductDraftForm = {
  nameHint: string;
  /** '' 는 고르지 않음 */
  categoryId: string;
  measurementsNote: string;
  origin: string;
  tone: string;
  /** 업로드 확정이 돌려준 공개 주소(`products/media/...`). null 은 빈 칸 */
  thumbnail: string | null;
  gallery: string[];
} & Record<ListField, string[]>;

export const emptyDraftForm = (): ProductDraftForm => ({
  nameHint: '',
  categoryId: '',
  measurementsNote: '',
  origin: '',
  tone: '',
  thumbnail: null,
  gallery: [],
  materials: [],
  careNotes: [],
  certifications: [],
  mustInclude: [],
  mustAvoid: [],
});

export type DraftImageRequest = { url: string; imageType: DraftImageType };

export type ProductDraftRequest = {
  nameHint?: string;
  categoryId?: string;
  measurementsNote?: string;
  origin?: string;
  tone?: string;
  images?: DraftImageRequest[];
} & Partial<Record<ListField, string[]>>;

// ---------- 문구 검사 ----------

// BE `ProductDraftValidationMessage.FORBIDDEN` 과 같다 — 제어 문자(C0 · DEL · C1), 짝 없는 서로게이트, 줄 · 문단 구분자,
// 방향 제어 문자, 태그 문자. 이모지의 ZWJ(U+200D) · 변형 선택자는 막지 않는다(상품 폼의 `isPlainText` 보다 좁다).
const FORBIDDEN =
  /[\p{Cc}\p{Cs}\u2028\u2029\u200E\u200F\u202A-\u202E\u2066-\u2069\u{E0000}-\u{E007F}]/u;

/** 한 줄 문구 — 개행 · 탭도 막는다 */
export const isSingleLine = (text: string) => !FORBIDDEN.test(text);
/** 치수 메모 — 개행(`\n` · `\r`) · 탭만 더 받는다 */
export const isMultiLine = (text: string) => !FORBIDDEN.test(text.replace(/[\t\n\r]/g, ''));

export const INVALID_CHARACTER =
  '줄바꿈 · 탭이나 보이지 않는 문자(제어 · 방향 문자)는 쓸 수 없습니다. 붙여 넣은 글이면 직접 다시 입력해 주세요.';
export const MEASUREMENTS_INVALID_CHARACTER =
  '줄바꿈 · 탭 외의 보이지 않는 문자(제어 · 방향 문자)는 쓸 수 없습니다. 붙여 넣은 글이면 직접 다시 입력해 주세요.';
export const tooLong = (max: number) => `${max}자 이하로 입력해 주세요.`;
export const LIST_ITEM_EMPTY = '내용을 입력한 뒤 더해 주세요.';
export const LIST_ITEM_DUPLICATED = '이미 더한 항목입니다.';
export const LIST_FULL = `${LIST_ITEMS_MAX}개까지 넣을 수 있습니다.`;
export const LIST_ITEM_INVALID = `항목은 1~${LIST_ITEM_MAX}자 한 줄로, 보이지 않는 문자 없이 입력해 주세요.`;
export const IMAGES_TOO_MANY = `사진은 대표를 포함해 ${DRAFT_IMAGES_MAX}장까지 넣을 수 있습니다.`;
export const IMAGE_DUPLICATED = '같은 사진을 두 번 넣을 수 없습니다. 겹친 사진을 빼 주세요.';
export const IMAGE_TOO_LARGE = '사진은 한 장에 5MB 이하만 넣을 수 있습니다.';

/** 한 줄 칸 하나 검사. 문제 없으면 null */
function singleLineError(value: string, max: number): string | null {
  if (!isSingleLine(value)) return INVALID_CHARACTER;
  if (value.length > max) return tooLong(max);
  return null;
}

// ---------- 목록 칸 ----------

export type ListAdd = { ok: true; items: string[] } | { ok: false; message: string };

/** 칩 하나 더하기 — 앞뒤 공백을 빼고 1~100자 한 줄, 10개까지, 같은 항목은 한 번만 */
export function addListItem(items: readonly string[], input: string): ListAdd {
  const value = input.trim();
  if (value === '') return { ok: false, message: LIST_ITEM_EMPTY };
  if (!isSingleLine(value)) return { ok: false, message: INVALID_CHARACTER };
  if (value.length > LIST_ITEM_MAX) return { ok: false, message: tooLong(LIST_ITEM_MAX) };
  if (items.includes(value)) return { ok: false, message: LIST_ITEM_DUPLICATED };
  if (items.length >= LIST_ITEMS_MAX) return { ok: false, message: LIST_FULL };
  return { ok: true, items: [...items, value] };
}

export const removeListItem = (items: readonly string[], index: number) =>
  items.filter((_, at) => at !== index);

// ---------- 사진 ----------

/** 대표 → 추가 순서의 본문 이미지. 빈 칸은 뺀다 */
export function draftImages(form: Pick<ProductDraftForm, 'thumbnail' | 'gallery'>) {
  return [
    ...(form.thumbnail ? [{ url: form.thumbnail, imageType: 'THUMBNAIL' as const }] : []),
    ...form.gallery.map((url) => ({ url, imageType: 'GALLERY' as const })),
  ];
}

/** 업로드 전 검사 — 형식은 업로드 칸이 본다. 초안은 장당 5MB 까지라 그보다 큰 파일은 올리지 않는다 */
export function checkDraftImageFile(file: Pick<File, 'size'>): string | null {
  return file.size > DRAFT_IMAGE_MAX_BYTES ? IMAGE_TOO_LARGE : null;
}

// ---------- 본문 ----------

export type BuildDraft =
  { ok: true; request: ProductDraftRequest } | { ok: false; errors: FormErrors };

/**
 * 폼 → 본문. 빈 칸 · 빈 목록 · 사진 없음은 보내지 않는다(서버도 빈 문구를 null 로 본다).
 * 어기는 칸이 있으면 보내지 않고 칸별 오류를 돌려준다(키는 본문 칸 이름, 사진은 `images`).
 */
export function draftRequest(form: ProductDraftForm): BuildDraft {
  const errors: FormErrors = {};
  const request: ProductDraftRequest = {};

  const single: [key: 'nameHint' | 'origin' | 'tone', max: number][] = [
    ['nameHint', NAME_HINT_MAX],
    ['origin', ORIGIN_MAX],
    ['tone', TONE_MAX],
  ];
  for (const [key, max] of single) {
    const value = form[key].trim();
    if (value === '') continue;
    const error = singleLineError(value, max);
    if (error) addError(errors, key, error);
    else request[key] = value;
  }

  const note = form.measurementsNote.trim();
  if (note !== '') {
    if (!isMultiLine(note)) addError(errors, 'measurementsNote', MEASUREMENTS_INVALID_CHARACTER);
    else if (note.length > MEASUREMENTS_NOTE_MAX) {
      addError(errors, 'measurementsNote', tooLong(MEASUREMENTS_NOTE_MAX));
    } else request.measurementsNote = note;
  }

  if (form.categoryId !== '' && isLongId(form.categoryId)) request.categoryId = form.categoryId;

  for (const key of LIST_FIELDS) {
    const items = form[key].map((item) => item.trim());
    if (items.length === 0) continue;
    if (items.length > LIST_ITEMS_MAX) addError(errors, key, LIST_FULL);
    if (items.some((item) => item === '' || singleLineError(item, LIST_ITEM_MAX) !== null)) {
      addError(errors, key, LIST_ITEM_INVALID);
    }
    request[key] = items;
  }

  const images = draftImages(form);
  if (images.length > DRAFT_IMAGES_MAX) addError(errors, 'images', IMAGES_TOO_MANY);
  if (new Set(images.map((image) => image.url)).size !== images.length) {
    addError(errors, 'images', IMAGE_DUPLICATED);
  }
  if (images.length > 0) request.images = images;

  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, request };
}
