import type { OperatorRole } from '@/lib/auth/permissions';

/**
 * 가게 운영자 계정(#352 · #237, BE #193 · #236 `AdminAccountAdminController`).
 * 목록은 운영자 전용 `GET /api/v1/admin/admins`(#647) — 역할 · 사용 여부 · OTP 등록 여부가 함께 온다. 이메일 · 이름은 서버가 가린 값이다.
 */
export const ADMIN_ACCOUNTS_PATH = '/api/v1/admin/admins';
export const MY_PASSWORD_PATH = '/api/v1/admin/me/password';
const PAGE_SIZE = 20;

/** BE `AdminAccountItem` */
export type AdminAccountItem = {
  /** Snowflake 문자열 — 숫자로 바꾸면 자릿수를 잃는다 */
  memberId: string;
  email: string;
  name: string;
  role: OperatorRole;
  active: boolean;
  deactivatedAt: string | null;
  otpEnrolled: boolean;
  createdAt: string;
};

/** 사용 여부 거르기. null 이면 전체 */
export type ActiveFilter = boolean | null;

const memberPath = (memberId: string) => `${ADMIN_ACCOUNTS_PATH}/${encodeURIComponent(memberId)}`;

/** 역할 변경 `PATCH {role}` */
export function rolePath(memberId: string): string {
  return `${memberPath(memberId)}/role`;
}

/** 사용 여부 변경 `PATCH {active}` — 끄기 · 다시 켜기 둘 다 */
export function statusPath(memberId: string): string {
  return `${memberPath(memberId)}/status`;
}

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** 주소의 페이지. 숫자 글자 6자리까지만 받고 나머지는 첫 페이지다(회원 목록과 같은 규칙) */
export function parseAdminPage(params: SearchParams): number {
  const raw = first(params.page);
  return raw && /^\d{1,6}$/.test(raw) ? Number(raw) : 0;
}

/** 주소의 사용 여부. `true` · `false` 만 받고 나머지는 전체다(BE 는 다른 값을 400 `AUTH_198` 로 거절한다) */
export function parseAdminActive(params: SearchParams): ActiveFilter {
  const raw = first(params.active);
  return raw === 'true' ? true : raw === 'false' ? false : null;
}

function query(page: number, active: ActiveFilter): URLSearchParams {
  const params = new URLSearchParams();
  if (active !== null) params.set('active', String(active));
  if (page > 0) params.set('page', String(page));
  return params;
}

export function adminsHref(page: number, active: ActiveFilter = null): string {
  const params = query(page, active).toString();
  return params ? `/admins?${params}` : '/admins';
}

export function adminsApiPath(page: number, active: ActiveFilter = null): string {
  const params = query(0, active);
  params.set('page', String(page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_ACCOUNTS_PATH}?${params}`;
}
