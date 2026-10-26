import { membersApiPath } from '@/lib/member/query';

/**
 * 가게 운영자 계정(#352, BE #193 `AdminAccountAdminController`). 목록은 따로 없고 회원 목록 `role=ADMIN` 을 쓴다.
 * 그 응답에는 활성 여부 칸이 없어 비활성화한 관리자도 목록에 그대로 보인다(이슈 #352 알려진 한계).
 */
export const ADMIN_ACCOUNTS_PATH = '/api/v1/admin/admins';
export const MY_PASSWORD_PATH = '/api/v1/admin/me/password';

/** 비활성화 경로. 아이디는 서버가 준 문자열 그대로다(Snowflake — 숫자로 바꾸면 자릿수를 잃는다) */
export function deactivatePath(memberId: string): string {
  return `${ADMIN_ACCOUNTS_PATH}/${encodeURIComponent(memberId)}`;
}

type SearchParams = Record<string, string | string[] | undefined>;

/** 주소의 페이지. 숫자 글자 6자리까지만 받고 나머지는 첫 페이지다(회원 목록과 같은 규칙) */
export function parseAdminPage(params: SearchParams): number {
  const raw = Array.isArray(params.page) ? params.page[0] : params.page;
  return raw && /^\d{1,6}$/.test(raw) ? Number(raw) : 0;
}

export function adminsHref(page: number): string {
  return page > 0 ? `/admins?page=${page}` : '/admins';
}

export function adminsApiPath(page: number): string {
  return membersApiPath({ role: 'ADMIN', page });
}
