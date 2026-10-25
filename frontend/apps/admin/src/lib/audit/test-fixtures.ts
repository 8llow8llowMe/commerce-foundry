import type { AuditLogItem } from './types';

/** 테스트용 감사 로그 한 행 — 아이디는 지어낸 값이다 */
export function auditLog(overrides: Partial<AuditLogItem> = {}): AuditLogItem {
  return {
    auditLogId: '900',
    action: 'MEMBER_SEARCH',
    actorMemberId: '101',
    target: 'members?role=ALL&page=0&size=20',
    result: 'SUCCESS',
    createdAt: '2026-10-24T03:00:00Z',
    ...overrides,
  };
}
