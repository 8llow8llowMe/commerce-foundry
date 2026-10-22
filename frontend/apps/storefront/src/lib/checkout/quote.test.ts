import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import type { CartLine, CartResponse } from '@/lib/cart/types';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { orderLines, quoteDestination, quoteFailure, quoteRequest } from './quote';

const won = (amount: number) => ({ amount, currency: 'KRW' as const });
const line = (itemId: string, patch: Partial<CartLine> = {}): CartLine => ({
  itemId,
  skuId: `9${itemId}`,
  productId: `1${itemId}`,
  productName: `상품 ${itemId}`,
  optionName: null,
  quantity: 1,
  unitPrice: won(10000),
  lineAmount: won(10000),
  status: { code: 'ON_SALE', name: '판매중', description: '' },
  maxQuantityPerOrder: null,
  customOrder: false,
  customOptions: [],
  quantityExceeded: false,
  ...patch,
});
const cart = (items: CartLine[]): CartResponse => ({
  items,
  totalAmount: null,
  purchasableCount: 0,
});

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, message, fieldErrors);

describe('orderLines', () => {
  it('구매 가능하고 상한 안인 줄만 주문서로 보내고, 나머지는 뺀 줄로 돌려준다(순서 유지)', () => {
    const soldOut = line('2', { status: { code: 'SOLD_OUT', name: '품절', description: '' } });
    const exceeded = line('3', { quantityExceeded: true });
    const { lines, excluded } = orderLines(cart([line('1'), soldOut, exceeded, line('4')]));
    expect(lines.map((item) => item.itemId)).toEqual(['1', '4']);
    expect(excluded.map((item) => item.itemId)).toEqual(['2', '3']);
  });
});

describe('quoteDestination', () => {
  it('우편번호는 앞뒤 공백을 빼고, 비면 null 이다', () => {
    expect(quoteDestination({ countryCode: 'KR', postalCode: ' 06236 ' })).toEqual({
      countryCode: 'KR',
      postalCode: '06236',
    });
    expect(quoteDestination({ countryCode: 'US', postalCode: '  ' })).toEqual({
      countryCode: 'US',
      postalCode: null,
    });
    expect(quoteDestination({ countryCode: 'JP', postalCode: null }).postalCode).toBeNull();
  });
});

describe('quoteRequest', () => {
  it('skuId 는 문자열 그대로, 맞춤 입력은 있을 때만 싣는다', () => {
    const custom = line('2', {
      customOrder: true,
      quantity: 2,
      customOptions: [{ fieldKey: 'height', value: '160' }],
    });
    expect(quoteRequest([line('1'), custom], { countryCode: 'KR', postalCode: '06236' })).toEqual({
      items: [
        { skuId: '91', quantity: 1 },
        { skuId: '92', quantity: 2, customOptions: [{ fieldKey: 'height', value: '160' }] },
      ],
      destination: { countryCode: 'KR', postalCode: '06236' },
    });
  });
});

describe('quoteFailure', () => {
  const t = ko.checkout;
  const ko_ = (cause: unknown) => quoteFailure(cause, t, 'ko');

  it('401 은 세션 끝, 429 · 503 · 500 · 결과 모름 · 연결 실패는 다시 계산이다', () => {
    expect(ko_(apiError('unauthorized', 401, 'SECURITY_001', null))).toEqual({ kind: 'signedOut' });
    expect(ko_(apiError('rateLimited', 429, 'GATEWAY_006', '많음'))).toEqual({
      kind: 'retry',
      message: t.errors.GATEWAY_006,
    });
    expect(ko_(apiError('unavailable', 503, 'ORDER_011', '서버 문구'))).toEqual({
      kind: 'retry',
      message: t.errors.ORDER_011,
    });
    for (const cause of [
      apiError('server', 500, 'ORDER_999', '서버'),
      apiError('unknownOutcome', 504, null, null),
      apiError('network', null, null, null),
      new Error('boom'),
    ]) {
      expect(ko_(cause)).toEqual({ kind: 'retry', message: t.quoteFailed });
    }
  });

  it('배송지 오류(정책 없음 409 · 국가 · 통화 400)는 배송지 자리다', () => {
    expect(ko_(apiError('conflict', 409, 'ORDER_012', '서버'))).toEqual({
      kind: 'destination',
      message: t.errors.ORDER_012,
    });
    for (const code of ['ORDER_013', 'ORDER_014']) {
      expect(ko_(apiError('server', 400, code, '서버'))).toEqual({
        kind: 'destination',
        message: t.errors[code],
      });
    }
  });

  it('배송지 칸 검증은 fieldErrors 의 코드로 배송지 자리에 둔다(배송지 없음 포함)', () => {
    const postal = apiError('validation', 400, 'ORDER_124', '형식', [
      { field: 'destination.postalCode', code: 'ORDER_124', message: '형식' },
    ]);
    expect(ko_(postal)).toEqual({ kind: 'destination', message: t.errors.ORDER_124 });
    const missing = apiError('validation', 400, 'ORDER_116', '배송지는 필수입니다.', [
      { field: 'destination', code: 'ORDER_116', message: '배송지는 필수입니다.' },
    ]);
    // 국문 사전에 없으면 서버 문구, 영문은 범용 문구
    expect(ko_(missing)).toEqual({ kind: 'destination', message: '배송지는 필수입니다.' });
    expect(quoteFailure(missing, en.checkout, 'en')).toEqual({
      kind: 'destination',
      message: en.checkout.destinationInvalid,
    });
  });

  it('무게 초과는 수량을 고칠 곳(장바구니) 안내다', () => {
    expect(ko_(apiError('server', 400, 'ORDER_015', '서버'))).toEqual({
      kind: 'cart',
      message: t.errors.ORDER_015,
    });
  });

  it('상품 이름 목록이 붙는 코드는 국문에서 서버 문구를, 영문에서 사전 문구를 쓴다', () => {
    for (const [code, status, kind] of [
      ['ORDER_003', 409, 'conflict'],
      ['ORDER_004', 400, 'server'],
      ['ORDER_005', 409, 'conflict'],
      ['ORDER_007', 409, 'conflict'],
      ['ORDER_008', 400, 'server'],
    ] as const) {
      const cause = apiError(kind, status, code, `서버 문구: 윤금 드레스(블랙 / S)`);
      expect(ko_(cause)).toEqual({ kind: 'cart', message: '서버 문구: 윤금 드레스(블랙 / S)' });
      expect(quoteFailure(cause, en.checkout, 'en')).toEqual({
        kind: 'cart',
        message: en.checkout.errors[code],
      });
    }
  });

  it('그 밖의 검증(줄 · 맞춤 입력 형식)은 장바구니 확인 안내다', () => {
    const cause = apiError('validation', 400, 'ORDER_107', '수량', [
      { field: 'items[0].quantity', code: 'ORDER_107', message: '수량' },
    ]);
    expect(ko_(cause)).toEqual({ kind: 'cart', message: t.cartInvalid });
  });
});
