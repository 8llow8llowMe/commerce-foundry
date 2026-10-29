/**
 * 어드민 관리자 감사 로그 `GET /api/v1/admin/audit-logs?action=&actorMemberId=&target=&result=&page=&size=`
 * (#192 · #286 · #322, auth-service `AdminAuditLogAdminController`, 계약 정본 `backend/docs/api-design-guide.md` §2-4 · `security-guide.md` §11-4).
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

/** 서버 `AdminAuditResult` 와 같은 값 — 필터에 보낼 수 있는 값이다(틀리면 400 AUTH_198) */
export const AUDIT_RESULTS = ['SUCCESS', 'FAILURE'] as const;
export type AuditResultCode = (typeof AUDIT_RESULTS)[number];

/** 결과의 업무 이름(#305) */
export const AUDIT_RESULT_LABELS: Record<AuditResultCode, string> = {
  SUCCESS: '성공',
  FAILURE: '실패',
};

/**
 * 감사 로그 한 행(`AdminAuditLogAdminItem`). 아이디는 문자열, 시각은 UTC ISO 다.
 * 이름은 서버가 마스킹한 표시 이름(#322)만 온다 — 원문 이름은 없다.
 * `action` · `result` 는 enum 이름이지만 모르는 값이 와도 화면이 깨지지 않게 string 으로 받는다.
 */
export type AuditLogItem = {
  auditLogId: string;
  action: string;
  /** 행위한 관리자 회원 아이디. 로그인 실패처럼 모르면 null — 화면에 그대로 보이지 않는다 */
  actorMemberId: string | null;
  /** 행위한 관리자의 마스킹 이름(#322). 행위자를 모르거나 이름이 없으면 null */
  actorDisplayName: string | null;
  /** 대상 원문(`member:<id>` · `members?role=..&page=..&size=..`). 없으면 null — 화면에 그대로 보이지 않는다 */
  target: string | null;
  /** `target` 이 `member:<id>` 일 때만 그 회원의 마스킹 이름(#322). 그 밖이거나 이름이 없으면 null */
  targetDisplayName: string | null;
  /** `SUCCESS` · `FAILURE` */
  result: string;
  createdAt: string;
};
