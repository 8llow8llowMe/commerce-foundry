import { describe, expect, it } from 'vitest';
import { lookCartLines, lookPickable, type LookProduct } from './look-cart';
import type { ProductSku, SaleStatusCode } from './types';

const krw = (amount: number) => ({ amount, currency: 'KRW' as const });
const status = (code: SaleStatusCode) => ({ code, name: code, description: '' });
const sku = (skuId: string, ids: string[], code: SaleStatusCode = 'ON_SALE'): ProductSku => ({
  skuId,
  optionValueIds: ids,
  additionalPrice: krw(0),
  salePrice: krw(89000),
  saleStatus: status(code),
});

const sized = (productId: string, overrides: Partial<LookProduct> = {}): LookProduct => ({
  productId,
  name: `상품 ${productId}`,
  thumbnail: null,
  salePrice: krw(89000),
  saleStatus: status('ON_SALE'),
  optionGroups: [
    {
      optionGroupId: 'size',
      name: '사이즈',
      displayOrder: 0,
      values: [
        { optionValueId: 's', name: 'S', displayOrder: 0 },
        { optionValueId: 'm', name: 'M', displayOrder: 1 },
      ],
    },
  ],
  skus: [sku(`${productId}-s`, ['s']), sku(`${productId}-m`, ['m'], 'SOLD_OUT')],
  customOrder: false,
  ...overrides,
});

describe('lookPickable', () => {
  it('판매중이고 맞춤 제작이 아니어야 한다', () => {
    expect(lookPickable(sized('1'))).toBe(true);
    expect(lookPickable(sized('1', { saleStatus: status('SOLD_OUT') }))).toBe(false);
    expect(lookPickable(sized('1', { customOrder: true }))).toBe(false);
  });
});

describe('lookCartLines', () => {
  it('고른 상품마다 옵션 조합의 SKU 를 수량 1 로 낸다', () => {
    expect(
      lookCartLines([
        { product: sized('1'), included: true, selection: { size: 's' } },
        { product: sized('2'), included: true, selection: { size: 's' } },
      ]),
    ).toEqual({
      lines: [
        { productId: '1', skuId: '1-s', quantity: 1 },
        { productId: '2', skuId: '2-s', quantity: 1 },
      ],
      incomplete: [],
    });
  });

  it('옵션 없는 상품은 선택 없이 하나뿐인 SKU 를 담는다', () => {
    const plain = sized('3', { optionGroups: [], skus: [sku('3-only', [])] });
    expect(lookCartLines([{ product: plain, included: true, selection: {} }]).lines).toEqual([
      { productId: '3', skuId: '3-only', quantity: 1 },
    ]);
  });

  it('옵션을 덜 골랐거나 품절 조합이면 incomplete 에 이름을 모은다', () => {
    const result = lookCartLines([
      { product: sized('1'), included: true, selection: {} },
      { product: sized('2'), included: true, selection: { size: 'm' } },
    ]);
    expect(result.lines).toEqual([]);
    expect(result.incomplete).toEqual(['상품 1', '상품 2']);
  });

  it('빼둔 상품 · 살 수 없는 상품 · 맞춤 제작 상품은 건너뛴다', () => {
    const result = lookCartLines([
      { product: sized('1'), included: false, selection: {} },
      { product: sized('2', { saleStatus: status('SOLD_OUT') }), included: true, selection: {} },
      { product: sized('3', { customOrder: true }), included: true, selection: {} },
    ]);
    expect(result).toEqual({ lines: [], incomplete: [] });
  });
});
