/**
 * 어드민 관리자 감사 로그 `GET /api/v1/admin/audit-logs?action=&actorMemberId=&page=&size=`
 * (#192, auth-service `AdminAuditLogAdminController`, 계약 정본 `backend/docs/api-design-guide.md` §2-4 · `security-guide.md` §11-4).
 * ADMIN 이고, 최신순(감사 로그 아이디 내림차순) `PageResponse` 다. 읽기만 있다.
 */
export const ADMIN_AUDIT_LOGS_PATH = '/api/v1/admin/audit-logs';

/** 서버 `AdminAuditAction` 과 같은 값 — 필터에 보낼 수 있는 값이다(틀리면 400 AUTH_198) */
export const AUDIT_ACTIONS = ['ADMIN_LOGIN', 'MEMBER_SEARCH'] as const;
export type AuditActionCode = (typeof AUDIT_ACTIONS)[number];

/** 행위의 업무 이름(#305). 서버가 이름을 주지 않아 화면이 옮겨 둔다 — BE 가 행위를 더하면 같이 더한다 */
export const AUDIT_ACTION_LABELS: Record<AuditActionCode, string> = {
  ADMIN_LOGIN: '관리자 로그인',
  MEMBER_SEARCH: '회원 목록 조회',
};

/**
 * 감사 로그 한 행(`AdminAuditLogAdminItem`). 아이디는 문자열, 시각은 UTC ISO 다. 개인정보는 없다.
 * `action` · `result` 는 enum 이름이지만 모르는 값이 와도 화면이 깨지지 않게 string 으로 받는다.
 */
export type AuditLogItem = {
  auditLogId: string;
  action: string;
  /** 행위한 관리자 회원 아이디. 로그인 실패처럼 모르면 null — 화면에 그대로 보이지 않는다 */
  actorMemberId: string | null;
  /** 대상 원문(`member:<id>` · `members?role=..&page=..&size=..`). 없으면 null — 화면에 그대로 보이지 않는다 */
  target: string | null;
  /** `SUCCESS` · `FAILURE` */
  result: string;
  createdAt: string;
};
