import { describe, expect, it } from 'vitest';
import { adminsApiPath, adminsHref, deactivatePath, parseAdminPage } from './query';

describe('admin-account query', () => {
  it('목록은 회원 목록 role=ADMIN 이고 페이지 · 크기를 늘 보낸다', () => {
    expect(adminsApiPath(0)).toBe('/api/v1/admin/members?role=ADMIN&page=0&size=20');
    expect(adminsApiPath(2)).toBe('/api/v1/admin/members?role=ADMIN&page=2&size=20');
  });

  it('주소 페이지는 숫자 글자만 받는다', () => {
    expect(parseAdminPage({ page: '3' })).toBe(3);
    expect(parseAdminPage({ page: ['1', '2'] })).toBe(1);
    expect(parseAdminPage({ page: '-1' })).toBe(0);
    expect(parseAdminPage({ page: '1e3' })).toBe(0);
    expect(parseAdminPage({})).toBe(0);
    expect(adminsHref(0)).toBe('/admins');
    expect(adminsHref(4)).toBe('/admins?page=4');
  });

  it('비활성화 경로는 아이디 문자열을 그대로 쓴다(자릿수를 잃지 않는다)', () => {
    expect(deactivatePath('1234567890123456789')).toBe('/api/v1/admin/admins/1234567890123456789');
  });
});
