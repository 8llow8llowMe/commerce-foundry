import { describe, expect, it } from 'vitest';
import {
  adminsApiPath,
  adminsHref,
  parseAdminActive,
  parseAdminPage,
  rolePath,
  statusPath,
} from './query';

describe('admin-account query', () => {
  it('목록은 운영자 목록이고 페이지 · 크기를 늘 보낸다. 사용 여부는 고를 때만', () => {
    expect(adminsApiPath(0)).toBe('/api/v1/admin/admins?page=0&size=20');
    expect(adminsApiPath(2, true)).toBe('/api/v1/admin/admins?active=true&page=2&size=20');
    expect(adminsApiPath(0, false)).toBe('/api/v1/admin/admins?active=false&page=0&size=20');
  });

  it('주소 페이지는 숫자 글자만 받는다', () => {
    expect(parseAdminPage({ page: '3' })).toBe(3);
    expect(parseAdminPage({ page: ['1', '2'] })).toBe(1);
    expect(parseAdminPage({ page: '-1' })).toBe(0);
    expect(parseAdminPage({ page: '1e3' })).toBe(0);
    expect(parseAdminPage({})).toBe(0);
  });

  it('주소 사용 여부는 true · false 만 받는다', () => {
    expect(parseAdminActive({ active: 'true' })).toBe(true);
    expect(parseAdminActive({ active: 'false' })).toBe(false);
    expect(parseAdminActive({ active: 'TRUE' })).toBeNull();
    expect(parseAdminActive({ active: ['false', 'true'] })).toBe(false);
    expect(parseAdminActive({})).toBeNull();
  });

  it('화면 주소는 기본값을 붙이지 않는다', () => {
    expect(adminsHref(0)).toBe('/admins');
    expect(adminsHref(4)).toBe('/admins?page=4');
    expect(adminsHref(0, false)).toBe('/admins?active=false');
    expect(adminsHref(1, true)).toBe('/admins?active=true&page=1');
  });

  it('변경 경로는 아이디 문자열을 그대로 쓴다(자릿수를 잃지 않는다)', () => {
    expect(rolePath('1234567890123456789')).toBe('/api/v1/admin/admins/1234567890123456789/role');
    expect(statusPath('1/2')).toBe('/api/v1/admin/admins/1%2F2/status');
  });
});
