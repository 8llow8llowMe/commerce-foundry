/**
 * 어드민 회원 목록 `GET /api/v1/admin/members`(#6 · #191, auth-service `MemberAdminController`).
 * 최신 가입순이고, 역할을 주지 않으면 고객 · 관리자 모두다. 이메일 · 이름 · 휴대폰은 서버가 마스킹한 값이다.
 */
export const ADMIN_MEMBERS_PATH = '/api/v1/admin/members';
/** 서버 기본값과 같다(1~100) */
export const PAGE_SIZE = 20;

export const MEMBER_ROLES = ['CUSTOMER', 'ADMIN'] as const;
export type MemberRole = (typeof MEMBER_ROLES)[number];

export const ROLE_LABELS: Record<MemberRole, string> = { CUSTOMER: '고객', ADMIN: '관리자' };

/** 역할의 국문 이름. 모르는 역할(서버에 새로 생긴 값)은 이름 그대로 보인다 */
export function roleLabel(role: string): string {
  return MEMBER_ROLES.find((item) => item === role) ? ROLE_LABELS[role as MemberRole] : role;
}

/** 화면 조건. role null 은 "전체"(서버에 보내지 않는다) */
export type MemberQuery = {
  role: MemberRole | null;
  page: number;
};

export const DEFAULT_QUERY: MemberQuery = { role: null, page: 0 };

/** `AdminMemberItem` — 개인정보는 마스킹한 값이다 */
export type MemberListItem = {
  memberId: string;
  email: string;
  name: string;
  /** 없으면 null */
  phone: string | null;
  /** enum 이름 그대로 온다 — 모르는 값이 와도 화면이 깨지지 않게 string 으로 받는다 */
  role: string;
  createdAt: string;
};

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/**
 * 주소(query string) → 조건. 허용 값만 받고 나머지는 "전체"(기본값)로 본다 — 손으로 고친 주소가 400(`AUTH_198` 등)이 되지 않게.
 * 역할은 대소문자까지 같아야 하고(서버 enum 바인딩), 페이지는 숫자 글자 6자리까지만 받는다.
 */
export function parseMemberQuery(params: SearchParams): MemberQuery {
  const role = first(params.role);
  const page = first(params.page);
  return {
    role: MEMBER_ROLES.find((item) => item === role) ?? null,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/** 조건 → 주소 파라미터. 기본값은 빼서 주소를 짧게 둔다 */
function queryParams(query: MemberQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.role) params.set('role', query.role);
  if (query.page > 0) params.set('page', String(query.page));
  return params;
}

/** 화면 주소 */
export function membersHref(query: MemberQuery): string {
  const search = queryParams(query).toString();
  return search ? `/members?${search}` : '/members';
}

/** BFF 경로. 페이지 · 크기는 늘 보낸다 */
export function membersApiPath(query: MemberQuery): string {
  const params = queryParams(query);
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_MEMBERS_PATH}?${params.toString()}`;
}
