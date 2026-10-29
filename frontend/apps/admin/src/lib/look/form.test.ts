import { describe, expect, it } from 'vitest';
import type { ProductFormItem } from '@/lib/display/form';
import {
  createLookForm,
  DESCRIPTION_CHARACTER,
  editLookForm,
  IMAGE_REQUIRED,
  IMAGE_URL_FORMAT,
  LOOK_TOO_LONG,
  lookRequest,
  lookSummary,
  lookWarnings,
  PRODUCT_COUNT,
  PRODUCT_DUPLICATED,
  SINGLE_LINE_ONLY,
  TITLE_REQUIRED,
  unchangedLookRequest,
  type LookForm,
} from './form';
import type { LookAdmin } from './types';

const IMAGE = 'https://media.example.test/looks/a.jpg';
const ON_SALE = { code: 'ON_SALE', name: '판매중', description: '' };
const STOP = { code: 'STOP_SELLING', name: '판매 종료', description: '' };

const product = (productId: string, visible = true): ProductFormItem => ({
  productId,
  name: `상품 ${productId}`,
  visible,
  availability: null,
  caption: null,
});

const filled = (patch: Partial<LookForm> = {}): LookForm => ({
  ...createLookForm(),
  title: '  가을 룩  ',
  imageUrl: ` ${IMAGE} `,
  products: [product('4611686018427387904'), product('2')],
  ...patch,
});

const look = (patch: Partial<LookAdmin> = {}): LookAdmin => ({
  lookId: '4611686018427387905',
  title: '가을 룩',
  description: '첫 줄\n둘째 줄',
  image: { url: IMAGE, width: 1200, height: 1600, altText: '가을 코디' },
  visible: true,
  products: [
    { productId: '11', name: '저고리', visible: true, saleStatus: ON_SALE },
    { productId: '12', name: '치마', visible: false, saleStatus: STOP },
  ],
  ...patch,
});

describe('createLookForm', () => {
  it('새 룩은 숨김 · 빈 칸으로 시작한다', () => {
    expect(createLookForm()).toEqual({
      title: '',
      description: '',
      imageUrl: '',
      altText: '',
      visible: false,
      imageSize: null,
      products: [],
    });
  });
});

describe('editLookForm', () => {
  it('저장된 룩을 폼으로 옮기고 판매 상태를 상품 고르기의 판매 상태 칸에 둔다', () => {
    const form = editLookForm(look());
    expect(form.title).toBe('가을 룩');
    expect(form.description).toBe('첫 줄\n둘째 줄');
    expect(form.imageSize).toEqual({ url: IMAGE, width: 1200, height: 1600 });
    expect(form.products[1]).toEqual({
      productId: '12',
      name: '치마',
      visible: false,
      availability: STOP,
      caption: null,
    });
  });

  it('원본 크기를 모르거나 선택 칸이 없으면 비운다', () => {
    const form = editLookForm(
      look({ description: null, image: { url: IMAGE, width: null, height: 10, altText: null } }),
    );
    expect(form.description).toBe('');
    expect(form.altText).toBe('');
    expect(form.imageSize).toBeNull();
  });
});

describe('lookRequest', () => {
  it('앞뒤 공백을 빼고, 빈 선택 칸은 null, 상품은 고른 순서대로 문자열 아이디로 보낸다', () => {
    expect(lookRequest(filled({ description: '   ', altText: '' }))).toEqual({
      ok: true,
      request: {
        title: '가을 룩',
        description: null,
        image: { url: IMAGE, width: null, height: null, altText: null },
        visible: false,
        productIds: ['4611686018427387904', '2'],
      },
    });
  });

  it('원본 크기는 지금 주소의 크기일 때만 싣는다', () => {
    const same = lookRequest(filled({ imageSize: { url: IMAGE, width: 800, height: 600 } }));
    expect(same.ok && same.request.image).toMatchObject({ width: 800, height: 600 });
    const other = lookRequest(
      filled({ imageSize: { url: 'https://media.example.test/old.jpg', width: 1, height: 1 } }),
    );
    expect(other.ok && other.request.image).toMatchObject({ width: null, height: null });
  });

  it('필수 칸(제목 · 이미지 주소)과 상품 수를 막는다', () => {
    expect(lookRequest(filled({ title: ' ', imageUrl: '', products: [] }))).toEqual({
      ok: false,
      errors: { title: [TITLE_REQUIRED], imageUrl: [IMAGE_REQUIRED], products: [PRODUCT_COUNT] },
    });
    const many = Array.from({ length: 21 }, (_, index) => product(String(index + 1)));
    expect(lookRequest(filled({ products: many }))).toEqual({
      ok: false,
      errors: { products: [PRODUCT_COUNT] },
    });
  });

  it('길이 · 제어 문자 · 중복 상품을 BE 와 같게 막는다(설명만 줄바꿈 허용)', () => {
    const result = lookRequest(
      filled({
        title: 'a'.repeat(101),
        description: '줄\n바꿈\t탭\u0007',
        imageUrl: 'https://media.example.test/a b.jpg',
        altText: '한\n줄',
        products: [product('1'), product('1')],
      }),
    );
    expect(result).toEqual({
      ok: false,
      errors: {
        title: [LOOK_TOO_LONG.title],
        description: [DESCRIPTION_CHARACTER],
        imageUrl: [IMAGE_URL_FORMAT],
        altText: [SINGLE_LINE_ONLY],
        products: [PRODUCT_DUPLICATED],
      },
    });
    expect(lookRequest(filled({ description: '첫 줄\n\t둘째 줄' })).ok).toBe(true);
  });
});

describe('unchangedLookRequest', () => {
  it('저장된 칸 · 이미지 · 상품 순서를 그대로 싣고 노출만 바꾼다', () => {
    expect(unchangedLookRequest(look(), false)).toEqual({
      title: '가을 룩',
      description: '첫 줄\n둘째 줄',
      image: { url: IMAGE, width: 1200, height: 1600, altText: '가을 코디' },
      visible: false,
      productIds: ['11', '12'],
    });
  });
});

describe('lookWarnings · lookSummary', () => {
  it('구성 상품이 모두 숨김이면 알린다', () => {
    expect(lookWarnings(filled({ products: [product('1', false)] }))).toHaveLength(1);
    expect(lookWarnings(filled())).toEqual([]);
    expect(lookWarnings(filled({ products: [] }))).toEqual([]);
  });

  it('상품 수와 숨긴 상품 수를 요약한다', () => {
    expect(lookSummary(look())).toBe('상품 2개 · 숨긴 상품 1개');
    expect(lookSummary(look({ products: [] }))).toBe('상품 0개');
  });
});
