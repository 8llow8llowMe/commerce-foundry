import { ADMIN_AUDIT_LOGS_PATH, AUDIT_ACTIONS, type AuditActionCode } from './types';

/** 서버 기본값과 같다(1~100) */
export const PAGE_SIZE = 20;

/** 화면 조건. null 은 "전체"(서버에 보내지 않는다) */
export type AuditLogQuery = {
  action: AuditActionCode | null;
  /** 관리자 회원 아이디(Long 범위 양의 정수 문자열) */
  actorMemberId: string | null;
  page: number;
};

export const DEFAULT_QUERY: AuditLogQuery = { action: null, actorMemberId: null, page: 0 };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** 양의 정수이고 서버 Long 범위 안인지 — 아니면 서버가 400(AUTH_135 · AUTH_198)이다 */
export function isMemberId(value: string): boolean {
  return /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9_223_372_036_854_775_807n;
}

/**
 * 주소(query string) → 조건. 허용 값만 받고 나머지는 "전체"(기본값)로 본다 — 손으로 고친 주소가 400 이 되지 않게.
 * 행위는 대소문자까지 같아야 하고(서버 enum 바인딩), 관리자는 Long 범위 양의 정수, 페이지는 숫자 글자 6자리까지만 받는다.
 */
export function parseAuditLogQuery(params: SearchParams): AuditLogQuery {
  const action = first(params.action);
  const actor = first(params.actor);
  const page = first(params.page);
  return {
    action: AUDIT_ACTIONS.find((item) => item === action) ?? null,
    actorMemberId: actor && isMemberId(actor) ? actor : null,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/** 화면 주소. 기본값은 빼서 주소를 짧게 둔다 */
export function auditLogsHref(query: AuditLogQuery): string {
  const params = new URLSearchParams();
  if (query.action) params.set('action', query.action);
  if (query.actorMemberId) params.set('actor', query.actorMemberId);
  if (query.page > 0) params.set('page', String(query.page));
  const search = params.toString();
  return search ? `/audit-logs?${search}` : '/audit-logs';
}

/** BFF 경로. 서버 파라미터 이름을 쓰고, 페이지 · 크기는 늘 보낸다 */
export function auditLogsApiPath(query: AuditLogQuery): string {
  const params = new URLSearchParams();
  if (query.action) params.set('action', query.action);
  if (query.actorMemberId) params.set('actorMemberId', query.actorMemberId);
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_AUDIT_LOGS_PATH}?${params.toString()}`;
}

export function hasFilter(query: AuditLogQuery): boolean {
  return query.action !== null || query.actorMemberId !== null;
}
