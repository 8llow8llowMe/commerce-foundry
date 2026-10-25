import {
  ADMIN_AUDIT_LOGS_PATH,
  AUDIT_ACTIONS,
  AUDIT_RESULTS,
  type AuditActionCode,
  type AuditResultCode,
} from './types';

/** 서버 기본값과 같다(1~100) */
export const PAGE_SIZE = 20;

/**
 * 화면 조건. null 은 "전체"(서버에 보내지 않는다).
 * 관리자 조건(서버 `actorMemberId`)은 두지 않는다 — 관리자 이름을 고를 수단이 BE #322(감사 로그 표시 이름) 뒤에 생긴다.
 * 대상 조건(서버 `target`, #286)도 두지 않는다 — 원문(`member:<id>`)을 다뤄야 해서 #305(아이디 · 코드 원문을 보이지 않는다)와 맞지 않는다.
 */
export type AuditLogQuery = {
  action: AuditActionCode | null;
  result: AuditResultCode | null;
  page: number;
};

export const DEFAULT_QUERY: AuditLogQuery = { action: null, result: null, page: 0 };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/**
 * 주소(query string) → 조건. 허용 값만 받고 나머지는 "전체"(기본값)로 본다 — 손으로 고친 주소가 400(`AUTH_198` 등)이 되지 않게.
 * 행위 · 결과는 대소문자까지 같아야 하고(서버 enum 바인딩), 페이지는 숫자 글자 6자리까지만 받는다.
 * 모르는 파라미터(옛 `actor`, 화면이 쓰지 않는 `target` 등)는 무시한다.
 */
export function parseAuditLogQuery(params: SearchParams): AuditLogQuery {
  const action = first(params.action);
  const result = first(params.result);
  const page = first(params.page);
  return {
    action: AUDIT_ACTIONS.find((item) => item === action) ?? null,
    result: AUDIT_RESULTS.find((item) => item === result) ?? null,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/** 조건 → 주소 파라미터. 기본값은 빼서 주소를 짧게 둔다 */
function queryParams(query: AuditLogQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.action) params.set('action', query.action);
  if (query.result) params.set('result', query.result);
  if (query.page > 0) params.set('page', String(query.page));
  return params;
}

/** 화면 주소 */
export function auditLogsHref(query: AuditLogQuery): string {
  const search = queryParams(query).toString();
  return search ? `/audit-logs?${search}` : '/audit-logs';
}

/** BFF 경로. 페이지 · 크기는 늘 보낸다 */
export function auditLogsApiPath(query: AuditLogQuery): string {
  const params = queryParams(query);
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_AUDIT_LOGS_PATH}?${params.toString()}`;
}

export function hasFilter(query: AuditLogQuery): boolean {
  return query.action !== null || query.result !== null;
}
