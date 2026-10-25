import type { AdminDirectory, AdminEntry } from './admins';
import type { AuditLogItem } from './types';

/** 테스트용 감사 로그 한 행 — 아이디 · 이름은 지어낸 값이다 */
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

export const ADMIN_A: AdminEntry = { memberId: '101', name: '김*수', email: 'ad***@e***.com' };
export const ADMIN_B: AdminEntry = { memberId: '102', name: '이*', email: 'op***@e***.com' };

export function directory(
  entries: AdminEntry[] = [ADMIN_A, ADMIN_B],
  complete = true,
): AdminDirectory {
  return { byId: new Map(entries.map((entry) => [entry.memberId, entry])), complete };
}
