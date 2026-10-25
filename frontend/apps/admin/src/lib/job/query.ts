import { ADMIN_JOBS_PATH, JOB_STATUSES, type JobStatusCode } from './types';

/** 서버 기본값과 같다(1~100) */
export const PAGE_SIZE = 20;
/** 서버 `@Pattern("[A-Z0-9_]{1,64}")` — 어긋나면 400 JOB_103 */
export const JOB_TYPE_PATTERN = /^[A-Z0-9_]{1,64}$/;
export const JOB_TYPE_MAX = 64;
export const JOB_TYPE_FORMAT_ERROR =
  '작업 종류는 영문 대문자 · 숫자 · 밑줄 64자 이내로 입력해 주세요.';

/** 화면 조건. null 은 "전체"(서버에 보내지 않는다) */
export type JobQuery = {
  status: JobStatusCode | null;
  type: string | null;
  page: number;
};

export const DEFAULT_QUERY: JobQuery = { status: null, type: null, page: 0 };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/**
 * 주소(query string) → 조건. 허용 값만 받고 나머지는 "전체"(기본값)로 본다 — 손으로 고친 주소가 400(JOB_103 · 198)이 되지 않게.
 * 상태는 대소문자까지 같아야 하고(서버 enum 바인딩), 종류는 서버 패턴 그대로, 페이지는 숫자 글자 6자리까지만 받는다.
 */
export function parseJobQuery(params: SearchParams): JobQuery {
  const status = first(params.status);
  const type = first(params.type);
  const page = first(params.page);
  return {
    status: JOB_STATUSES.find((item) => item === status) ?? null,
    type: type && JOB_TYPE_PATTERN.test(type) ? type : null,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/**
 * 종류 입력 칸 → 조건 값. 앞뒤 공백을 빼고 대문자로 바꾼다(핸들러 이름은 대문자다). 비우면 전체(null),
 * 패턴에 맞지 않으면 `ok: false` — 화면이 칸 오류로 보이고 주소를 바꾸지 않는다.
 */
export function normalizeJobType(input: string): { ok: true; type: string | null } | { ok: false } {
  const text = input.trim().toUpperCase();
  if (text === '') return { ok: true, type: null };
  return JOB_TYPE_PATTERN.test(text) ? { ok: true, type: text } : { ok: false };
}

/** 조건 → 주소 파라미터. 기본값은 빼서 주소를 짧게 둔다 */
function queryParams(query: JobQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.status) params.set('status', query.status);
  if (query.type) params.set('type', query.type);
  if (query.page > 0) params.set('page', String(query.page));
  return params;
}

/** 화면 주소 */
export function jobsHref(query: JobQuery): string {
  const search = queryParams(query).toString();
  return search ? `/jobs?${search}` : '/jobs';
}

/** BFF 경로. 페이지 · 크기는 늘 보낸다 */
export function jobsApiPath(query: JobQuery): string {
  const params = queryParams(query);
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_JOBS_PATH}?${params.toString()}`;
}

export function hasFilter(query: JobQuery): boolean {
  return query.status !== null || query.type !== null;
}
