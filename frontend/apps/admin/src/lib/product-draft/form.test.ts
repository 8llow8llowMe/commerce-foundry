import { describe, expect, it } from 'vitest';
import {
  addListItem,
  checkDraftImageFile,
  draftImages,
  draftRequest,
  emptyDraftForm,
  IMAGE_DUPLICATED,
  IMAGE_TOO_LARGE,
  IMAGES_TOO_MANY,
  INVALID_CHARACTER,
  isMultiLine,
  isSingleLine,
  LIST_FULL,
  LIST_ITEM_DUPLICATED,
  LIST_ITEM_EMPTY,
  LIST_ITEM_INVALID,
  MEASUREMENTS_INVALID_CHARACTER,
  productDraftHref,
  productDraftPath,
  removeListItem,
  tooLong,
  type ProductDraftForm,
} from './form';

const form = (patch: Partial<ProductDraftForm> = {}): ProductDraftForm => ({
  ...emptyDraftForm(),
  ...patch,
});
const media = (n: number) => `https://cdn.example.test/products/media/7/u${n}/h${n}.jpg`;

describe('문구 검사 (BE ProductDraftValidationMessage 와 같은 규칙)', () => {
  it.each([
    ['개행', 'a\nb'],
    ['탭', 'a\tb'],
    ['NEL(U+0085)', 'a\u0085b'],
    ['줄 구분자', 'a\u2028b'],
    ['문단 구분자', 'a\u2029b'],
    ['방향 제어(RLO)', 'a\u202Eb'],
    ['방향 제어(LRM)', 'a\u200Eb'],
    ['방향 격리(U+2066)', 'a\u2066b'],
    ['태그 문자', `a${String.fromCodePoint(0xe0041)}b`],
    ['짝 없는 서로게이트', 'a\uD800b'],
  ])('한 줄 칸은 %s 를 받지 않는다', (_, text) => {
    expect(isSingleLine(text)).toBe(false);
  });

  it('이모지 ZWJ · 변형 선택자 · 한글은 받는다', () => {
    expect(isSingleLine('가족 👨\u200D👩\u200D👧 ❤️ 린넨')).toBe(true);
  });

  it('치수 메모는 개행 · 탭만 더 받는다', () => {
    expect(isMultiLine('총장 62cm\r\n가슴\t54cm')).toBe(true);
    expect(isMultiLine('총장\u202E62cm')).toBe(false);
    expect(isMultiLine('총장\u000b62cm')).toBe(false);
  });
});

describe('addListItem · removeListItem', () => {
  it('앞뒤 공백을 빼고 더한다', () => {
    expect(addListItem(['면'], '  린넨 ')).toEqual({ ok: true, items: ['면', '린넨'] });
  });

  it('빈 값 · 보이지 않는 문자 · 100자 초과 · 중복 · 10개 초과를 막는다', () => {
    expect(addListItem([], '   ')).toEqual({ ok: false, message: LIST_ITEM_EMPTY });
    expect(addListItem([], 'a\u202Eb')).toEqual({ ok: false, message: INVALID_CHARACTER });
    expect(addListItem([], 'x'.repeat(101))).toEqual({ ok: false, message: tooLong(100) });
    expect(addListItem([], 'x'.repeat(100)).ok).toBe(true);
    expect(addListItem(['면'], '면')).toEqual({ ok: false, message: LIST_ITEM_DUPLICATED });
    const ten = Array.from({ length: 10 }, (_, i) => `항목${i}`);
    expect(addListItem(ten, '하나 더')).toEqual({ ok: false, message: LIST_FULL });
  });

  it('번호로 뺀다', () => {
    expect(removeListItem(['a', 'b', 'c'], 1)).toEqual(['a', 'c']);
  });
});

describe('사진', () => {
  it('대표 → 추가 순서로 본문 이미지를 만든다', () => {
    expect(draftImages({ thumbnail: media(1), gallery: [media(2), media(3)] })).toEqual([
      { url: media(1), imageType: 'THUMBNAIL' },
      { url: media(2), imageType: 'GALLERY' },
      { url: media(3), imageType: 'GALLERY' },
    ]);
    expect(draftImages({ thumbnail: null, gallery: [media(2)] })).toEqual([
      { url: media(2), imageType: 'GALLERY' },
    ]);
  });

  it('장당 5MB 를 넘는 파일은 올리기 전에 막는다', () => {
    expect(checkDraftImageFile({ size: 5 * 1024 * 1024 })).toBeNull();
    expect(checkDraftImageFile({ size: 5 * 1024 * 1024 + 1 })).toBe(IMAGE_TOO_LARGE);
  });
});

describe('draftRequest', () => {
  it('빈 폼은 빈 본문이다 — 모든 칸이 선택이다', () => {
    expect(draftRequest(form())).toEqual({ ok: true, request: {} });
  });

  it('채운 칸만 앞뒤 공백을 빼고 싣는다', () => {
    const built = draftRequest(
      form({
        nameHint: '  린넨 저고리 ',
        categoryId: '12',
        measurementsNote: ' 총장 62cm\n가슴 54cm ',
        origin: '대한민국',
        tone: '   ',
        materials: ['린넨 100%'],
        mustAvoid: ['최고'],
        thumbnail: media(1),
        gallery: [media(2)],
      }),
    );
    expect(built).toEqual({
      ok: true,
      request: {
        nameHint: '린넨 저고리',
        categoryId: '12',
        measurementsNote: '총장 62cm\n가슴 54cm',
        origin: '대한민국',
        materials: ['린넨 100%'],
        mustAvoid: ['최고'],
        images: [
          { url: media(1), imageType: 'THUMBNAIL' },
          { url: media(2), imageType: 'GALLERY' },
        ],
      },
    });
  });

  it('칸 규칙을 어기면 보내지 않고 그 칸에 오류를 단다', () => {
    const built = draftRequest(
      form({
        nameHint: '저고리\n지시문',
        origin: 'x'.repeat(101),
        tone: 'y'.repeat(201),
        measurementsNote: '총장\u2028 62cm',
        careNotes: ['ok', 'a\u202Eb'],
      }),
    );
    expect(built).toEqual({
      ok: false,
      errors: {
        nameHint: [INVALID_CHARACTER],
        origin: [tooLong(100)],
        tone: [tooLong(200)],
        measurementsNote: [MEASUREMENTS_INVALID_CHARACTER],
        careNotes: [LIST_ITEM_INVALID],
      },
    });
  });

  it('치수 메모는 1000자까지', () => {
    expect(draftRequest(form({ measurementsNote: 'x'.repeat(1000) })).ok).toBe(true);
    expect(draftRequest(form({ measurementsNote: 'x'.repeat(1001) }))).toEqual({
      ok: false,
      errors: { measurementsNote: [tooLong(1000)] },
    });
  });

  it('형식이 틀린 카테고리 값은 싣지 않는다', () => {
    expect(draftRequest(form({ categoryId: 'abc' }))).toEqual({ ok: true, request: {} });
  });

  it('사진은 8장까지, 같은 사진 두 번은 막는다', () => {
    const many = Array.from({ length: 8 }, (_, i) => media(i + 2));
    expect(draftRequest(form({ thumbnail: media(1), gallery: many }))).toEqual({
      ok: false,
      errors: { images: [IMAGES_TOO_MANY] },
    });
    expect(draftRequest(form({ thumbnail: media(1), gallery: [media(1)] }))).toEqual({
      ok: false,
      errors: { images: [IMAGE_DUPLICATED] },
    });
  });
});

describe('경로', () => {
  it('BFF 경로와 화면 주소', () => {
    expect(productDraftPath('42')).toBe('/api/v1/admin/product-drafts/42');
    expect(productDraftHref('42')).toBe('/products/drafts/42');
  });
});
