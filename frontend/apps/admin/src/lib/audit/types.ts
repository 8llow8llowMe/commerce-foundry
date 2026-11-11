/**
 * 어드민 관리자 감사 로그 `GET /api/v1/admin/audit-logs?action=&actorMemberId=&target=&result=&page=&size=`
 * (#192 · #286 · #322, auth-service `AdminAuditLogAdminController`, 계약 정본 `backend/docs/api-design-guide.md` §2-4 · `security-guide.md` §11-4).
 * ADMIN 이고, 최신순(감사 로그 아이디 내림차순) `PageResponse` 다. 읽기만 있다.
 */
export const ADMIN_AUDIT_LOGS_PATH = '/api/v1/admin/audit-logs';

/**
 * 서버 `AdminAuditAction`(auth-service, 15종)과 같은 값 — 필터에 보낼 수 있는 값은 이 목록뿐이다(틀리면 400 AUTH_198).
 * 순서는 필터 묶음(`AUDIT_ACTION_GROUPS`) 순서와 같다.
 */
export const AUDIT_ACTIONS = [
  'ADMIN_LOGIN',
  'ADMIN_LOGIN_PASSWORD_VERIFIED',
  'ADMIN_CREATE',
  'ADMIN_DEACTIVATE',
  'ADMIN_REACTIVATE',
  'ADMIN_ROLE_CHANGE',
  'ADMIN_SEARCH',
  'ADMIN_PASSWORD_CHANGE',
  'ADMIN_PASSWORD_RESET_LINK_SEND',
  'ADMIN_PASSWORD_RESET_USE',
  'ADMIN_OTP_ENROLL',
  'ADMIN_OTP_DISABLE',
  'ADMIN_OTP_RECOVERY_REGENERATE',
  'ADMIN_OTP_PEER_RECOVERY',
  'MEMBER_SEARCH',
] as const;
export type AuditActionCode = (typeof AUDIT_ACTIONS)[number];

/** 행위의 업무 이름(#305 · #712). 서버가 이름을 주지 않아 화면이 옮겨 둔다 — BE 가 행위를 더하면 같이 더한다 */
export const AUDIT_ACTION_LABELS: Record<AuditActionCode, string> = {
  ADMIN_LOGIN: '관리자 로그인',
  ADMIN_LOGIN_PASSWORD_VERIFIED: '로그인 1단계(비밀번호) 통과',
  ADMIN_CREATE: '운영자 추가',
  ADMIN_DEACTIVATE: '운영자 비활성화',
  ADMIN_REACTIVATE: '운영자 다시 켜기',
  ADMIN_ROLE_CHANGE: '운영자 역할 변경',
  ADMIN_SEARCH: '운영자 목록 조회',
  ADMIN_PASSWORD_CHANGE: '비밀번호 변경',
  ADMIN_PASSWORD_RESET_LINK_SEND: '비밀번호 재설정 링크 발송',
  ADMIN_PASSWORD_RESET_USE: '비밀번호 재설정 링크 사용',
  ADMIN_OTP_ENROLL: 'OTP 등록',
  ADMIN_OTP_DISABLE: 'OTP 해제',
  ADMIN_OTP_RECOVERY_REGENERATE: 'OTP 복구 코드 재발급',
  ADMIN_OTP_PEER_RECOVERY: '동료 OTP 복구',
  MEMBER_SEARCH: '회원 목록 조회',
};

/** 행위 필터 묶음(#712) — 고르기 쉽게 업무별로 나눈다. 모든 행위가 정확히 한 묶음에 든다 */
export const AUDIT_ACTION_GROUPS: readonly {
  label: string;
  actions: readonly AuditActionCode[];
}[] = [
  { label: '로그인', actions: ['ADMIN_LOGIN', 'ADMIN_LOGIN_PASSWORD_VERIFIED'] },
  {
    label: '운영자 계정',
    actions: [
      'ADMIN_CREATE',
      'ADMIN_DEACTIVATE',
      'ADMIN_REACTIVATE',
      'ADMIN_ROLE_CHANGE',
      'ADMIN_SEARCH',
    ],
  },
  {
    label: '보안(OTP · 비밀번호)',
    actions: [
      'ADMIN_PASSWORD_CHANGE',
      'ADMIN_PASSWORD_RESET_LINK_SEND',
      'ADMIN_PASSWORD_RESET_USE',
      'ADMIN_OTP_ENROLL',
      'ADMIN_OTP_DISABLE',
      'ADMIN_OTP_RECOVERY_REGENERATE',
      'ADMIN_OTP_PEER_RECOVERY',
    ],
  },
  { label: '회원', actions: ['MEMBER_SEARCH'] },
];

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
  /** 행위한 관리자 회원 아이디. 로그인 실패 · 인증 전 기록(로그인 1단계 통과 · 재설정 링크 사용)이면 null — 화면에 그대로 보이지 않는다 */
  actorMemberId: string | null;
  /** 행위한 관리자의 마스킹 이름(#322). 행위자를 모르거나 이름이 없으면 null */
  actorDisplayName: string | null;
  /** 대상 원문(`member:<id>` · `members?role=..&page=..&size=..` · `admins?active=..&page=..&size=..`). 없으면 null — 화면에 그대로 보이지 않는다 */
  target: string | null;
  /** `target` 이 `member:<id>` 일 때만 그 회원의 마스킹 이름(#322). 그 밖이거나 이름이 없으면 null */
  targetDisplayName: string | null;
  /** `SUCCESS` · `FAILURE` */
  result: string;
  createdAt: string;
};
