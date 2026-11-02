import { describe, expect, it } from 'vitest';
import {
  DEFAULT_QUERY,
  hasFilter,
  isDate,
  kstMidnightIso,
  orderHref,
  ordersApiPath,
  ordersHref,
  parseOrderQuery,
} from './query';

describe('parseOrderQuery', () => {
  it('빈 주소는 기본 조건이다', () => {
    expect(parseOrderQuery({})).toEqual(DEFAULT_QUERY);
  });

  it('허용 값은 그대로 읽는다', () => {
    expect(
      parseOrderQuery({ status: 'PREPARING', from: '2026-10-01', to: '2026-10-31', page: '2' }),
    ).toEqual({ status: 'PREPARING', from: '2026-10-01', to: '2026-10-31', page: 2 });
  });

  it('모르는 상태 · 달력에 없는 날짜 · 숫자가 아닌 페이지는 전체(기본값)다', () => {
    expect(
      parseOrderQuery({ status: 'paid', from: '2026-02-30', to: '2026/10/31', page: '-1' }),
    ).toEqual(DEFAULT_QUERY);
  });

  it('시작이 끝보다 늦으면 끝을 버린다(서버 ORDER_146)', () => {
    expect(parseOrderQuery({ from: '2026-10-31', to: '2026-10-01' })).toEqual({
      ...DEFAULT_QUERY,
      from: '2026-10-31',
    });
    expect(parseOrderQuery({ from: '2026-10-01', to: '2026-10-01' }).to).toBe('2026-10-01');
  });

  it('배열 파라미터는 첫 값만 본다', () => {
    expect(parseOrderQuery({ status: ['CANCELED', 'PAID'] }).status).toBe('CANCELED');
  });
});

describe('isDate · kstMidnightIso', () => {
  it('달력에 있는 날짜만 받는다', () => {
    expect(isDate('2028-02-29')).toBe(true);
    expect(isDate('2026-02-29')).toBe(false);
    expect(isDate(undefined)).toBe(false);
  });

  it('한국 자정을 UTC 시각으로 바꾸고, 달 · 해 경계를 넘긴다', () => {
    expect(kstMidnightIso('2026-10-01')).toBe('2026-09-30T15:00:00Z');
    expect(kstMidnightIso('2026-12-31', 1)).toBe('2026-12-31T15:00:00Z');
  });
});

describe('ordersHref · ordersApiPath', () => {
  const query = { status: 'PAID' as const, from: '2026-10-01', to: '2026-10-31', page: 1 };

  it('화면 주소는 기본값을 뺀다', () => {
    expect(ordersHref(DEFAULT_QUERY)).toBe('/orders');
    expect(ordersHref(query)).toBe('/orders?status=PAID&from=2026-10-01&to=2026-10-31&page=1');
  });

  it('API 경로는 기간을 [from, to + 1일) UTC 로 보내고 페이지 · 크기를 늘 싣는다', () => {
    expect(ordersApiPath(DEFAULT_QUERY)).toBe('/api/v1/admin/orders?page=0&size=20');
    const params = new URL(ordersApiPath(query), 'http://x').searchParams;
    expect(params.get('status')).toBe('PAID');
    expect(params.get('from')).toBe('2026-09-30T15:00:00Z');
    expect(params.get('to')).toBe('2026-10-31T15:00:00Z');
    expect(params.get('page')).toBe('1');
  });

  it('상세 주소는 아이디를 그대로 싣는다', () => {
    expect(orderHref('4611686018427387904')).toBe('/orders/4611686018427387904');
  });

  it('조건 유무', () => {
    expect(hasFilter({ ...DEFAULT_QUERY, page: 3 })).toBe(false);
    expect(hasFilter({ ...DEFAULT_QUERY, to: '2026-10-01' })).toBe(true);
  });
});
