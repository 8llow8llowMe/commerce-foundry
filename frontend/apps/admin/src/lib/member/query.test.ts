import { describe, expect, it } from 'vitest';
import {
  DEFAULT_QUERY,
  membersApiPath,
  membersHref,
  parseMemberQuery,
  roleLabel,
  type MemberQuery,
} from './query';

const FULL: MemberQuery = { role: 'ADMIN', page: 2 };

describe('parseMemberQuery', () => {
  it('빈 주소는 기본 조건이다', () => {
    expect(parseMemberQuery({})).toEqual(DEFAULT_QUERY);
  });

  it('허용 값은 그대로 읽는다', () => {
    expect(parseMemberQuery({ role: 'ADMIN', page: '2' })).toEqual(FULL);
    expect(parseMemberQuery({ role: 'CUSTOMER' }).role).toBe('CUSTOMER');
  });

  it('허용하지 않는 값은 전체(기본값)로 본다', () => {
    expect(parseMemberQuery({ role: 'admin', page: '-1' })).toEqual(DEFAULT_QUERY);
    expect(parseMemberQuery({ role: 'ROOT', page: '1.5' })).toEqual(DEFAULT_QUERY);
    expect(parseMemberQuery({ page: '1234567' })).toEqual(DEFAULT_QUERY);
  });

  it('여러 값이면 첫 값을 쓴다', () => {
    expect(parseMemberQuery({ role: ['CUSTOMER', 'ADMIN'], page: ['1', '2'] })).toEqual({
      role: 'CUSTOMER',
      page: 1,
    });
  });
});

describe('membersHref', () => {
  it('기본값은 주소에서 뺀다', () => {
    expect(membersHref(DEFAULT_QUERY)).toBe('/members');
  });

  it('조건을 주소로 쓰고, 다시 읽으면 같은 조건이다', () => {
    const href = membersHref(FULL);
    expect(href).toBe('/members?role=ADMIN&page=2');
    const params = Object.fromEntries(new URL(href, 'http://admin.test').searchParams);
    expect(parseMemberQuery(params)).toEqual(FULL);
  });
});

describe('membersApiPath', () => {
  it('페이지 · 크기는 늘 보내고, 전체(null) 역할은 보내지 않는다', () => {
    expect(membersApiPath(DEFAULT_QUERY)).toBe('/api/v1/admin/members?page=0&size=20');
  });

  it('조건을 서버 파라미터 이름 그대로 보낸다', () => {
    expect(membersApiPath(FULL)).toBe('/api/v1/admin/members?role=ADMIN&page=2&size=20');
  });
});

describe('roleLabel', () => {
  it('아는 역할은 국문 이름, 모르는 역할은 이름 그대로다', () => {
    expect(roleLabel('CUSTOMER')).toBe('고객');
    expect(roleLabel('ADMIN')).toBe('관리자');
    expect(roleLabel('SELLER')).toBe('SELLER');
    expect(roleLabel('toString')).toBe('toString');
  });
});
