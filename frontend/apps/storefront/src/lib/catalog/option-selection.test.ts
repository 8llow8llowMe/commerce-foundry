import { describe, expect, it } from 'vitest';
import { displayPrice, optionValueState, resolveSku, selectOption } from './option-selection';
import type { ProductDetail, ProductOptionGroup, ProductSku, SaleStatusCode } from './types';

const krw = (amount: number) => ({ amount, currency: 'KRW' as const });
const status = (code: SaleStatusCode) => ({ code, name: code, description: '' });

// 색상(아이보리 · 먹색) × 사이즈(S · M · L)
const GROUPS: ProductOptionGroup[] = [
  {
    optionGroupId: 'color',
    name: '색상',
    displayOrder: 0,
    values: [
      { optionValueId: 'ivory', name: '아이보리', displayOrder: 0 },
      { optionValueId: 'ink', name: '먹색', displayOrder: 1 },
    ],
  },
  {
    optionGroupId: 'size',
    name: '사이즈',
    displayOrder: 1,
    values: [
      { optionValueId: 's', name: 'S', displayOrder: 0 },
      { optionValueId: 'm', name: 'M', displayOrder: 1 },
      { optionValueId: 'l', name: 'L', displayOrder: 2 },
    ],
  },
];

const sku = (
  skuId: string,
  optionValueIds: string[],
  additional: number,
  code: SaleStatusCode = 'ON_SALE',
): ProductSku => ({
  skuId,
  optionValueIds,
  additionalPrice: krw(additional),
  salePrice: krw(129000 + additional),
  saleStatus: status(code),
});

const SKUS: ProductSku[] = [
  sku('ivory-s', ['ivory', 's'], 0),
  sku('ivory-m', ['ivory', 'm'], 0, 'SOLD_OUT'),
  sku('ivory-l', ['ivory', 'l'], 10000),
  sku('ink-s', ['ink', 's'], 0, 'STOP_SELLING'),
  sku('ink-m', ['ink', 'm'], 0),
  // 먹색 L 조합은 없다
];

const product = (overrides: Partial<ProductDetail> = {}): ProductDetail => ({
  productId: '1',
  name: '윤금 드레스',
  summary: null,
  categoryPath: [],
  thumbnail: null,
  images: [],
  descriptionHtml: null,
  salePrice: krw(129000),
  listPrice: krw(159000),
  discountRate: 18,
  saleStatus: status('ON_SALE'),
  maxQuantityPerOrder: null,
  optionGroups: GROUPS,
  skus: SKUS,
  customOrder: null,
  ...overrides,
});

describe('resolveSku', () => {
  it('모든 그룹을 고르면 그 조합의 SKU 를 찾는다', () => {
    expect(resolveSku(GROUPS, SKUS, { color: 'ivory', size: 'l' })?.skuId).toBe('ivory-l');
  });

  it('덜 골랐거나 없는 조합이면 null 이다', () => {
    expect(resolveSku(GROUPS, SKUS, { color: 'ivory' })).toBeNull();
    expect(resolveSku(GROUPS, SKUS, { color: 'ink', size: 'l' })).toBeNull();
  });

  it('옵션 없는 상품은 빈 선택으로 하나뿐인 SKU 를 찾는다', () => {
    const only = sku('only', [], 0);
    expect(resolveSku([], [only], {})?.skuId).toBe('only');
  });
});

describe('optionValueState', () => {
  it('아무것도 안 골랐으면 그 값이 들어간 SKU 중 판매중이 있는지로 본다', () => {
    expect(optionValueState(SKUS, {}, 'color', 'ivory')).toBe('available');
    expect(optionValueState(SKUS, {}, 'size', 'm')).toBe('available'); // ink-m 판매중
    expect(optionValueState(SKUS, {}, 'size', 'l')).toBe('available');
  });

  it('다른 그룹의 선택과 함께 본다 — 품절 · 판매 종료 · 없는 조합', () => {
    expect(optionValueState(SKUS, { color: 'ivory' }, 'size', 'm')).toBe('soldOut');
    expect(optionValueState(SKUS, { color: 'ink' }, 'size', 's')).toBe('unavailable');
    expect(optionValueState(SKUS, { color: 'ink' }, 'size', 'l')).toBe('unavailable');
    expect(optionValueState(SKUS, { size: 'l' }, 'color', 'ink')).toBe('unavailable');
  });

  it('같은 그룹의 현재 선택은 무시하고 바꿀 값으로 본다', () => {
    expect(optionValueState(SKUS, { color: 'ivory', size: 's' }, 'size', 'm')).toBe('soldOut');
    expect(optionValueState(SKUS, { color: 'ivory', size: 'm' }, 'size', 'l')).toBe('available');
  });
});

describe('selectOption', () => {
  it('값을 고르고 다른 그룹 선택은 그대로 둔다', () => {
    expect(selectOption(SKUS, { color: 'ivory' }, 'size', 'l')).toEqual({
      color: 'ivory',
      size: 'l',
    });
  });

  it('새 값과 함께 살 수 없게 된 다른 그룹의 선택은 푼다', () => {
    // 먹색 L 은 없다 → 먹색을 고른 상태에서 L 을 누르면 색상 선택이 풀린다
    expect(selectOption(SKUS, { color: 'ink' }, 'size', 'l')).toEqual({ size: 'l' });
  });

  it('원래 선택 객체는 바꾸지 않는다', () => {
    const before = { color: 'ink' };
    selectOption(SKUS, before, 'size', 'l');
    expect(before).toEqual({ color: 'ink' });
  });
});

describe('displayPrice', () => {
  it('SKU 를 정하기 전에는 상품 최저가 · 정가 · 할인율에 옵션 추가금이 있으면 from', () => {
    expect(displayPrice(product(), null)).toEqual({
      salePrice: krw(129000),
      listPrice: krw(159000),
      discountRate: 18,
      from: true,
    });
    expect(displayPrice(product({ skus: [sku('a', [], 0)] }), null).from).toBe(false);
  });

  it('SKU 를 정하면 그 SKU 의 서버 판매가를 그대로 쓴다', () => {
    const ivoryL = SKUS[2]!;
    expect(displayPrice(product(), ivoryL)).toEqual({
      salePrice: krw(139000),
      listPrice: null,
      discountRate: null,
      from: false,
    });
  });

  it('SKU 판매가가 상품 표시가와 같을 때만 정가 · 할인율을 붙인다', () => {
    expect(displayPrice(product(), SKUS[0]!)).toMatchObject({
      salePrice: krw(129000),
      listPrice: krw(159000),
      discountRate: 18,
    });
  });
});
