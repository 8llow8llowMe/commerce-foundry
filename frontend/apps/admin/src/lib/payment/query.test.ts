import { describe, expect, it } from 'vitest';
import {
  DEFAULT_QUERY,
  hasFilter,
  isOrderId,
  isPreset,
  parsePaymentQuery,
  paymentsApiPath,
  paymentsHref,
  PRESETS,
} from './query';

describe('parsePaymentQuery', () => {
  it('빈 주소는 기본 조건이다', () => {
    expect(parsePaymentQuery({})).toEqual(DEFAULT_QUERY);
  });

  it('허용 값은 그대로 읽는다', () => {
    expect(
      parsePaymentQuery({ status: 'UNKNOWN', orderId: '42', orderSynced: 'false', page: '3' }),
    ).toEqual({ status: 'UNKNOWN', orderId: '42', orderSynced: false, page: 3 });
    expect(parsePaymentQuery({ orderSynced: 'true' }).orderSynced).toBe(true);
  });

  it('모르는 상태 · REFUND_FAILED · 잘못된 주문 아이디 · 회신 값 · 페이지는 전체(기본값)다', () => {
    expect(
      parsePaymentQuery({ status: 'unknown', orderId: '0', orderSynced: 'yes', page: '-1' }),
    ).toEqual(DEFAULT_QUERY);
    // 서버가 아직 받지 않는 상태(400 PAYMENT_198)
    expect(parsePaymentQuery({ status: 'REFUND_FAILED' }).status).toBeNull();
  });

  it('배열 파라미터는 첫 값만 본다', () => {
    expect(parsePaymentQuery({ status: ['CANCELED', 'DONE'] }).status).toBe('CANCELED');
  });
});

describe('isOrderId', () => {
  it('양의 Long 범위 숫자만 받는다', () => {
    expect(isOrderId('1')).toBe(true);
    expect(isOrderId('4611686018427387904')).toBe(true);
    expect(isOrderId('9223372036854775807')).toBe(true);
    expect(isOrderId('9223372036854775808')).toBe(false);
    expect(isOrderId('10000000000000000000')).toBe(false);
    expect(isOrderId('012')).toBe(false);
    expect(isOrderId('-3')).toBe(false);
    expect(isOrderId('1e3')).toBe(false);
    expect(isOrderId(undefined)).toBe(false);
  });
});

describe('paymentsHref · paymentsApiPath', () => {
  const query = { status: 'DONE', orderId: '42', orderSynced: false, page: 2 } as const;

  it('화면 주소는 기본값을 뺀다', () => {
    expect(paymentsHref(DEFAULT_QUERY)).toBe('/payments');
    expect(paymentsHref(query)).toBe('/payments?status=DONE&orderId=42&orderSynced=false&page=2');
  });

  it('BFF 경로는 페이지 · 크기를 늘 보낸다', () => {
    expect(paymentsApiPath(DEFAULT_QUERY)).toBe('/api/v1/admin/payments?page=0&size=20');
    expect(paymentsApiPath(query)).toBe(
      '/api/v1/admin/payments?status=DONE&orderId=42&orderSynced=false&page=2&size=20',
    );
  });

  it('주소 → 조건 → 주소가 같다', () => {
    expect(
      paymentsHref(
        parsePaymentQuery(
          Object.fromEntries(
            new URLSearchParams('status=DONE&orderId=42&orderSynced=false&page=2'),
          ),
        ),
      ),
    ).toBe('/payments?status=DONE&orderId=42&orderSynced=false&page=2');
  });
});

describe('hasFilter · isPreset', () => {
  it('페이지만 있으면 조건이 없다', () => {
    expect(hasFilter({ ...DEFAULT_QUERY, page: 3 })).toBe(false);
    expect(hasFilter({ ...DEFAULT_QUERY, orderSynced: false })).toBe(true);
  });

  it('프리셋은 페이지를 보지 않고 조건만 견준다', () => {
    const [unknown] = PRESETS;
    expect(unknown && isPreset({ ...unknown.query, page: 4 }, unknown.query)).toBe(true);
    expect(unknown && isPreset({ ...unknown.query, orderId: '1' }, unknown.query)).toBe(false);
  });
});
