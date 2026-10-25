import { describe, expect, it } from 'vitest';
import {
  auditLogsApiPath,
  auditLogsHref,
  DEFAULT_QUERY,
  hasFilter,
  isMemberId,
  parseAuditLogQuery,
  type AuditLogQuery,
} from './query';

const FULL: AuditLogQuery = { action: 'MEMBER_SEARCH', actorMemberId: '101', page: 2 };

describe('parseAuditLogQuery', () => {
  it('빈 주소는 기본 조건이다', () => {
    expect(parseAuditLogQuery({})).toEqual(DEFAULT_QUERY);
  });

  it('허용 값은 그대로 읽는다', () => {
    expect(parseAuditLogQuery({ action: 'MEMBER_SEARCH', actor: '101', page: '2' })).toEqual(FULL);
    expect(parseAuditLogQuery({ action: 'ADMIN_LOGIN' }).action).toBe('ADMIN_LOGIN');
  });

  it('허용하지 않는 값은 전체(기본값)로 본다 — 서버 400(AUTH_135 · 198)을 만들지 않는다', () => {
    expect(parseAuditLogQuery({ action: 'admin_login', actor: '0', page: '-1' })).toEqual(
      DEFAULT_QUERY,
    );
    expect(parseAuditLogQuery({ action: 'JOB_CANCEL', actor: '-5', page: '1.5' })).toEqual(
      DEFAULT_QUERY,
    );
    expect(parseAuditLogQuery({ actor: '9223372036854775808', page: '1234567' })).toEqual(
      DEFAULT_QUERY,
    );
    expect(parseAuditLogQuery({ actor: '9223372036854775807' }).actorMemberId).toBe(
      '9223372036854775807',
    );
  });

  it('여러 값이면 첫 값을 쓴다', () => {
    expect(
      parseAuditLogQuery({ action: ['ADMIN_LOGIN', 'MEMBER_SEARCH'], actor: ['7', '8'] }),
    ).toEqual({ action: 'ADMIN_LOGIN', actorMemberId: '7', page: 0 });
  });
});

describe('isMemberId', () => {
  it('Long 범위 양의 정수 문자열만 받는다', () => {
    expect(isMemberId('1')).toBe(true);
    expect(isMemberId('4611686018427387904')).toBe(true);
    expect(isMemberId('')).toBe(false);
    expect(isMemberId('01')).toBe(false);
    expect(isMemberId('1e3')).toBe(false);
    expect(isMemberId('9223372036854775808')).toBe(false);
  });
});

describe('auditLogsHref', () => {
  it('기본값은 주소에서 뺀다', () => {
    expect(auditLogsHref(DEFAULT_QUERY)).toBe('/audit-logs');
  });

  it('조건을 주소로 쓰고, 다시 읽으면 같은 조건이다', () => {
    const href = auditLogsHref(FULL);
    expect(href).toBe('/audit-logs?action=MEMBER_SEARCH&actor=101&page=2');
    const params = Object.fromEntries(new URL(href, 'http://admin.test').searchParams);
    expect(parseAuditLogQuery(params)).toEqual(FULL);
  });
});

describe('auditLogsApiPath', () => {
  it('페이지 · 크기는 늘 보내고, 전체(null) 조건은 보내지 않는다', () => {
    expect(auditLogsApiPath(DEFAULT_QUERY)).toBe('/api/v1/admin/audit-logs?page=0&size=20');
  });

  it('조건을 서버 파라미터 이름(actorMemberId)으로 보낸다', () => {
    expect(auditLogsApiPath(FULL)).toBe(
      '/api/v1/admin/audit-logs?action=MEMBER_SEARCH&actorMemberId=101&page=2&size=20',
    );
  });
});

describe('hasFilter', () => {
  it('행위 · 관리자 중 하나라도 있으면 조건이 있다(페이지는 조건이 아니다)', () => {
    expect(hasFilter({ ...DEFAULT_QUERY, page: 3 })).toBe(false);
    expect(hasFilter({ ...DEFAULT_QUERY, action: 'ADMIN_LOGIN' })).toBe(true);
    expect(hasFilter({ ...DEFAULT_QUERY, actorMemberId: '1' })).toBe(true);
  });
});
