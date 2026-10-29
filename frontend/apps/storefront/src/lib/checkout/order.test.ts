import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { emptyDraft } from '@/lib/account/address-form';
import type { ShippingAddress } from '@/lib/account/types';
import type { CartLine } from '@/lib/cart/types';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import {
  draftShippingAddress,
  formatDueAt,
  nextAttempt,
  orderFailure,
  orderRequest,
  savedShippingAddress,
} from './order';

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
const saved: ShippingAddress = {
  addressId: '7',
  label: '집',
  recipientName: '홍길동',
  recipientPhone: '01012345678',
  countryCode: 'KR',
  postalCode: '06236',
  addressLine1: '서울특별시 강남구 테헤란로 1',
  addressLine2: null,
  city: null,
  stateProvince: null,
  isDefault: true,
};

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, message, fieldErrors);
const fail = (cause: unknown) => orderFailure(cause, ko.checkout, 'ko');

describe('주문 배송지', () => {
  it('저장된 배송지는 라벨 · 기본 여부 · 아이디를 빼고 싣는다', () => {
    expect(savedShippingAddress(saved)).toEqual({
      recipientName: '홍길동',
      recipientPhone: '01012345678',
      countryCode: 'KR',
      postalCode: '06236',
      addressLine1: '서울특별시 강남구 테헤란로 1',
      addressLine2: null,
      city: null,
      stateProvince: null,
    });
  });

  it('새 배송지는 공백을 정리하고 빈 칸은 null, 국내면 도시 · 주를 비운다', () => {
    const draft = {
      ...emptyDraft('KR'),
      label: '회사',
      recipientName: ' 김철수 ',
      recipientPhone: '010-1111-2222',
      postalCode: ' 63100 ',
      addressLine1: '제주시 첨단로 242 ',
      city: '숨은 도시',
    };
    expect(draftShippingAddress(draft)).toEqual({
      recipientName: '김철수',
      recipientPhone: '010-1111-2222',
      countryCode: 'KR',
      postalCode: '63100',
      addressLine1: '제주시 첨단로 242',
      addressLine2: null,
      city: null,
      stateProvince: null,
    });
  });
});

describe('orderRequest', () => {
  it('줄은 견적과 같은 모양이고, 기대 총액은 견적 총액 그대로다', () => {
    const custom = line('2', {
      customOrder: true,
      customOptions: [{ fieldKey: 'height', value: '170' }],
    });
    expect(
      orderRequest([line('1', { quantity: 2 }), custom], savedShippingAddress(saved), won(53000)),
    ).toEqual({
      items: [
        { skuId: '91', quantity: 2 },
        { skuId: '92', quantity: 1, customOptions: [{ fieldKey: 'height', value: '170' }] },
      ],
      shippingAddress: savedShippingAddress(saved),
      expectedTotalAmount: won(53000),
    });
  });
});

describe('nextAttempt', () => {
  let n = 0;
  const createKey = () => `key-${++n}`;

  it('앞 시도가 없으면 새 키를 만든다', () => {
    expect(nextAttempt(null, 'body', createKey).idempotencyKey).toMatch(/^key-/);
  });

  it('남겨 둔 시도와 본문이 같으면 같은 키를 이어 쓴다', () => {
    const first = nextAttempt(null, 'body', createKey);
    expect(nextAttempt(first, 'body', createKey)).toBe(first);
  });

  it('본문이 바뀌면(배송지 · 새 견적) 새 키다 — 같은 키 다른 본문은 409 ORDER_019', () => {
    const first = nextAttempt(null, 'body', createKey);
    const second = nextAttempt(first, 'other', createKey);
    expect(second.idempotencyKey).not.toBe(first.idempotencyKey);
    expect(second.body).toBe('other');
  });
});

describe('orderFailure', () => {
  it('401 이면 세션 끝', () => {
    expect(fail(apiError('unauthorized', 401, 'AUTH_001', null)).kind).toBe('signedOut');
  });

  it('금액 변경(409 ORDER_010)은 다시 견적할 실패이고 새 키다', () => {
    expect(fail(apiError('conflict', 409, 'ORDER_010', '주문 금액이 변경되었습니다.'))).toEqual({
      kind: 'amountChanged',
      message: ko.checkout.orderErrors.ORDER_010,
      sameKey: false,
    });
  });

  it('재고 부족(409 ORDER_016)은 장바구니로 안내하고 새 키다', () => {
    expect(fail(apiError('conflict', 409, 'ORDER_016', '재고가 부족한 상품이 있습니다.'))).toEqual({
      kind: 'stock',
      message: ko.checkout.orderErrors.ORDER_016,
      sameKey: false,
    });
  });

  it('처리 중(409 ORDER_018)은 같은 키로 다시 누른다', () => {
    expect(fail(apiError('conflict', 409, 'ORDER_018', null))).toEqual({
      kind: 'retry',
      message: ko.checkout.orderErrors.ORDER_018,
      sameKey: true,
    });
  });

  it.each([
    ['unknownOutcome', 504, null],
    ['unavailable', 503, 'ORDER_002'],
    ['server', 500, 'ORDER_021'],
    ['server', 500, 'ORDER_017'],
    ['network', null, null],
    ['invalidResponse', 200, null],
  ] as const)('%s(%s %s)는 반영 여부를 모르므로 같은 키로 다시 누른다', (kind, status, code) => {
    const failure = fail(apiError(kind, status, code, null));
    expect(failure.kind).toBe('retry');
    expect(failure.sameKey).toBe(true);
  });

  it('ApiError 가 아닌 예외도 같은 키로 다시 누른다', () => {
    expect(fail(new TypeError('boom'))).toEqual({
      kind: 'retry',
      message: ko.checkout.orderFailed,
      sameKey: true,
    });
  });

  it('429 GATEWAY_006 은 주문용 요청 제한 문구, 같은 키(요청이 서비스에 닿지 않았다)', () => {
    expect(fail(apiError('rateLimited', 429, 'GATEWAY_006', null))).toEqual({
      kind: 'retry',
      message: ko.checkout.orderErrors.GATEWAY_006,
      sameKey: true,
    });
  });

  it('배송지 칸 검증은 사전 문구로 배송지 실패 — 서버 문구(필드 이름)는 보이지 않는다', () => {
    const failure = fail(
      apiError('validation', 400, 'ORDER_100', '요청 값이 올바르지 않습니다.', [
        {
          field: 'shippingAddress.recipientPhone',
          code: 'ORDER_120',
          message: 'recipientPhone 은 …',
        },
      ]),
    );
    expect(failure).toEqual({
      kind: 'destination',
      message: ko.checkout.orderErrors.ORDER_120,
      sameKey: false,
    });
  });

  it('사전에 없는 배송지 칸 코드는 범용 배송지 문구다', () => {
    const failure = fail(
      apiError('validation', 400, 'ORDER_100', null, [
        { field: 'shippingAddress', code: 'ORDER_999', message: 'x' },
      ]),
    );
    expect(failure).toMatchObject({ kind: 'destination', message: ko.checkout.destinationInvalid });
  });

  it('배송 불가 국가(ORDER_013)는 견적과 같은 문구로 배송지 실패', () => {
    expect(fail(apiError('server', 400, 'ORDER_013', '배송할 수 없는 국가입니다.'))).toEqual({
      kind: 'destination',
      message: ko.checkout.errors.ORDER_013,
      sameKey: false,
    });
  });

  it('판매 불가(ORDER_003)는 국문에서 서버 문구(상품 이름 포함)로 장바구니 안내', () => {
    const message = '지금은 구매할 수 없는 상품이 있습니다: 상품 1';
    expect(fail(apiError('conflict', 409, 'ORDER_003', message))).toEqual({
      kind: 'cart',
      message,
      sameKey: false,
    });
  });

  it('영문 스토어는 서버 국문 대신 영문 사전 문구를 쓴다', () => {
    const failure = orderFailure(
      apiError('conflict', 409, 'ORDER_003', '지금은 구매할 수 없는 상품이 있습니다: 상품 1'),
      en.checkout,
      'en',
    );
    expect(failure).toMatchObject({ kind: 'cart', message: en.checkout.errors.ORDER_003 });
  });

  it('그 밖의 4xx(ORDER_019 키 재사용 등)는 이 시도를 끝내고 새 키로 다시 누를 수 있다', () => {
    expect(fail(apiError('conflict', 409, 'ORDER_019', null))).toEqual({
      kind: 'retry',
      message: ko.checkout.orderFailed,
      sameKey: false,
    });
  });
});

describe('formatDueAt', () => {
  it('언어별 날짜 · 시각으로 보인다', () => {
    const iso = '2026-10-18T09:30:00Z';
    expect(formatDueAt(iso, 'ko', 'Asia/Seoul')).toContain('6:30');
    expect(formatDueAt(iso, 'en', 'UTC')).toContain('9:30');
  });
});
