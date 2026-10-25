import { describe, expect, it } from 'vitest';
import {
  auditLogsApiPath,
  auditLogsHref,
  DEFAULT_QUERY,
  hasFilter,
  parseAuditLogQuery,
  type AuditLogQuery,
} from './query';

const FULL: AuditLogQuery = { action: 'MEMBER_SEARCH', page: 2 };

describe('parseAuditLogQuery', () => {
  it('빈 주소는 기본 조건이다', () => {
    expect(parseAuditLogQuery({})).toEqual(DEFAULT_QUERY);
  });

  it('허용 값은 그대로 읽는다', () => {
    expect(parseAuditLogQuery({ action: 'MEMBER_SEARCH', page: '2' })).toEqual(FULL);
    expect(parseAuditLogQuery({ action: 'ADMIN_LOGIN' }).action).toBe('ADMIN_LOGIN');
  });

  it('허용하지 않는 값은 전체(기본값)로 본다 — 서버 400(AUTH_198 등)을 만들지 않는다', () => {
    expect(parseAuditLogQuery({ action: 'admin_login', page: '-1' })).toEqual(DEFAULT_QUERY);
    expect(parseAuditLogQuery({ action: 'JOB_CANCEL', page: '1.5' })).toEqual(DEFAULT_QUERY);
    expect(parseAuditLogQuery({ page: '1234567' })).toEqual(DEFAULT_QUERY);
  });

  it('옛 주소의 관리자 조건(actor)은 무시한다', () => {
    expect(parseAuditLogQuery({ action: 'ADMIN_LOGIN', actor: '101', page: '1' })).toEqual({
      action: 'ADMIN_LOGIN',
      page: 1,
    });
    expect(parseAuditLogQuery({ actor: 'abc' })).toEqual(DEFAULT_QUERY);
  });

  it('여러 값이면 첫 값을 쓴다', () => {
    expect(
      parseAuditLogQuery({ action: ['ADMIN_LOGIN', 'MEMBER_SEARCH'], page: ['1', '2'] }),
    ).toEqual({ action: 'ADMIN_LOGIN', page: 1 });
  });
});

describe('auditLogsHref', () => {
  it('기본값은 주소에서 뺀다', () => {
    expect(auditLogsHref(DEFAULT_QUERY)).toBe('/audit-logs');
  });

  it('조건을 주소로 쓰고, 다시 읽으면 같은 조건이다', () => {
    const href = auditLogsHref(FULL);
    expect(href).toBe('/audit-logs?action=MEMBER_SEARCH&page=2');
    const params = Object.fromEntries(new URL(href, 'http://admin.test').searchParams);
    expect(parseAuditLogQuery(params)).toEqual(FULL);
  });
});

describe('auditLogsApiPath', () => {
  it('페이지 · 크기는 늘 보내고, 전체(null) 조건은 보내지 않는다', () => {
    expect(auditLogsApiPath(DEFAULT_QUERY)).toBe('/api/v1/admin/audit-logs?page=0&size=20');
  });

  it('행위를 서버 파라미터 이름 그대로 보내고, 관리자 조건(actorMemberId)은 보내지 않는다', () => {
    const path = auditLogsApiPath(FULL);
    expect(path).toBe('/api/v1/admin/audit-logs?action=MEMBER_SEARCH&page=2&size=20');
    expect(path).not.toContain('actorMemberId');
  });
});

describe('hasFilter', () => {
  it('행위가 있으면 조건이 있다(페이지는 조건이 아니다)', () => {
    expect(hasFilter({ ...DEFAULT_QUERY, page: 3 })).toBe(false);
    expect(hasFilter({ ...DEFAULT_QUERY, action: 'ADMIN_LOGIN' })).toBe(true);
  });
});
