import { describe, expect, it } from 'vitest';
import type { ProductDetail, SaleStatusCode } from '@/lib/catalog/types';
import { guestLineView, memberLineView } from './line-view';
import type { CartLine } from './types';

const krw = (amount: number) => ({ amount, currency: 'KRW' as const });
const status = (code: SaleStatusCode, name: string = code) => ({ code, name, description: '' });

const product = (overrides: Partial<ProductDetail> = {}): ProductDetail => ({
  productId: '10',
  name: '저고리',
  summary: null,
  categoryPath: [],
  thumbnail: null,
  images: [],
  descriptionHtml: null,
  salePrice: krw(100000),
  listPrice: null,
  discountRate: null,
  saleStatus: status('ON_SALE'),
  maxQuantityPerOrder: 3,
  optionGroups: [
    {
      optionGroupId: 'c',
      name: '색상',
      displayOrder: 0,
      values: [{ optionValueId: 'iv', name: '아이보리', displayOrder: 0 }],
    },
    {
      optionGroupId: 's',
      name: '사이즈',
      displayOrder: 1,
      values: [{ optionValueId: 'l', name: 'L', displayOrder: 0 }],
    },
  ],
  skus: [
    {
      skuId: '1001',
      optionValueIds: ['iv', 'l'],
      additionalPrice: krw(5000),
      salePrice: krw(105000),
      saleStatus: status('SOLD_OUT', '품절'),
    },
  ],
  customOrder: {
    notice: null,
    leadTimeDays: null,
    fields: [
      {
        fieldKey: 'waist',
        label: '허리 둘레',
        inputType: 'NUMBER',
        unit: 'cm',
        required: true,
        min: null,
        max: null,
        step: null,
        helpText: null,
      },
    ],
  },
  ...overrides,
});

const local = {
  skuId: '1001',
  productId: '10',
  quantity: 4,
  customOptions: [{ fieldKey: 'waist', value: '70' }],
  addedAt: 1,
};

describe('guestLineView', () => {
  it('상품 상세로 이름 · 옵션명(BE 와 같은 " / ") · 단가 · SKU 상태 · 입력 항목 이름을 채운다. 줄 금액은 계산하지 않는다', () => {
    const view = guestLineView(local, product());
    expect(view).toMatchObject({
      name: '저고리',
      optionName: '아이보리 / L',
      unitPrice: krw(105000),
      lineAmount: null,
      status: { code: 'SOLD_OUT' },
      maxQuantity: 3,
      quantityExceeded: true,
      customOptions: [{ label: '허리 둘레', value: '70' }],
    });
  });

  it('상품이 판매중이 아니면 상품 상태를 따른다', () => {
    expect(guestLineView(local, product({ saleStatus: status('STOP_SELLING') })).status?.code).toBe(
      'STOP_SELLING',
    );
  });

  it('상품이 없거나(404) SKU 가 사라졌으면 판매하지 않는 줄이다', () => {
    expect(guestLineView(local, null)).toMatchObject({ name: null, status: null, unitPrice: null });
    expect(guestLineView(local, product({ skus: [] }))).toMatchObject({
      name: '저고리',
      status: null,
    });
  });
});

describe('memberLineView', () => {
  it('서버 줄 금액을 그대로 쓰고, 상한이 없으면 99 다', () => {
    const line: CartLine = {
      itemId: '5',
      skuId: '1001',
      productId: '10',
      productName: '저고리',
      optionName: '',
      quantity: 2,
      unitPrice: krw(1000),
      lineAmount: krw(2000),
      status: status('ON_SALE'),
      maxQuantityPerOrder: null,
      customOrder: false,
      customOptions: [],
      quantityExceeded: false,
    };
    expect(memberLineView(line)).toMatchObject({
      key: '5',
      lineAmount: krw(2000),
      maxQuantity: 99,
    });
  });
});
