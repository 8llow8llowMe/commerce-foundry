import { describe, expect, it } from 'vitest';
import {
  claimHref,
  claimsApiPath,
  claimsHref,
  DEFAULT_QUERY,
  hasFilter,
  parseClaimQuery,
} from './query';

describe('parseClaimQuery', () => {
  it('빈 주소는 기본 조건이다', () => {
    expect(parseClaimQuery({})).toEqual(DEFAULT_QUERY);
  });

  it('허용 값은 그대로 읽는다', () => {
    expect(
      parseClaimQuery({ type: 'RETURN', status: 'REQUESTED', orderId: '42', page: '3' }),
    ).toEqual({ type: 'RETURN', status: 'REQUESTED', orderId: '42', page: 3 });
  });

  it('서버가 아직 받지 않는 유형 · 상태, 잘못된 주문 아이디 · 페이지는 전체(기본값)다', () => {
    expect(
      parseClaimQuery({ type: 'CANCEL', status: 'REFUND_FAILED', orderId: '0', page: '-1' }),
    ).toEqual(DEFAULT_QUERY);
    expect(parseClaimQuery({ orderId: '9223372036854775808' }).orderId).toBeNull();
    expect(parseClaimQuery({ page: '1234567' }).page).toBe(0);
  });

  it('배열 값은 첫 값만 본다', () => {
    expect(parseClaimQuery({ type: ['RETURN', 'X'] }).type).toBe('RETURN');
  });
});

describe('claimsHref · claimsApiPath', () => {
  it('화면 주소는 기본값을 뺀다', () => {
    expect(claimsHref(DEFAULT_QUERY)).toBe('/claims');
    expect(claimsHref({ ...DEFAULT_QUERY, status: 'REQUESTED', page: 2 })).toBe(
      '/claims?status=REQUESTED&page=2',
    );
  });

  it('BFF 경로는 페이지 · 크기를 늘 보낸다', () => {
    expect(claimsApiPath(DEFAULT_QUERY)).toBe('/api/v1/admin/claims?page=0&size=20');
    expect(claimsApiPath({ type: 'RETURN', status: null, orderId: '7', page: 1 })).toBe(
      '/api/v1/admin/claims?type=RETURN&orderId=7&page=1&size=20',
    );
  });

  it('상세 주소는 아이디를 인코딩한다', () => {
    expect(claimHref('4611686018427387904')).toBe('/claims/4611686018427387904');
  });

  it('hasFilter 는 페이지를 보지 않는다', () => {
    expect(hasFilter({ ...DEFAULT_QUERY, page: 3 })).toBe(false);
    expect(hasFilter({ ...DEFAULT_QUERY, orderId: '1' })).toBe(true);
  });
});
