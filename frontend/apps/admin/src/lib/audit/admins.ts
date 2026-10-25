import { apiFetch, type PageResponse } from '@cf/api-client';
import { ADMIN_MEMBERS_PATH, type MemberListItem } from '@/lib/member/query';

/**
 * 관리자 이름표 — 감사 로그 응답에는 관리자 회원 아이디만 있어(#192) 이름은 어드민 회원 목록(`role=ADMIN`)을 **화면에 들어올 때 한 번** 읽어 짝짓는다.
 * 줄마다 회원을 따로 읽지 않는다(N+1). 이름 · 이메일은 서버가 마스킹한 값이다.
 * 이 조회는 회원 목록 조회라 서버 감사 로그에 "회원 목록 조회 — 역할: 관리자" 한 줄로 남는다(BE 가 감사 로그에 이름 칸을 주면 걷어낸다).
 */
export const ADMIN_DIRECTORY_PATH = `${ADMIN_MEMBERS_PATH}?role=ADMIN&page=0&size=100`;

export type AdminEntry = { memberId: string; name: string; email: string };

export type AdminDirectory = {
  /** 회원 아이디 → 관리자 */
  byId: ReadonlyMap<string, AdminEntry>;
  /** 첫 페이지(100명)에 다 담겼는가 — 아니면 이름을 모르는 관리자가 있을 수 있다 */
  complete: boolean;
};

export function toAdminDirectory(page: PageResponse<MemberListItem>): AdminDirectory {
  const byId = new Map<string, AdminEntry>();
  for (const { memberId, name, email } of page.contents)
    byId.set(memberId, { memberId, name, email });
  return { byId, complete: page.totalElements <= page.contents.length };
}

/**
 * 관리자 이름표를 읽는다. 실패하면 null — 감사 로그는 이름 없이도 보이므로 화면을 막지 않는다
 * (401 · 403 은 감사 로그 목록이 같이 받아 `AccessNotice` 를 그린다).
 */
export async function loadAdminDirectory(signal?: AbortSignal): Promise<AdminDirectory | null> {
  try {
    return toAdminDirectory(
      await apiFetch<PageResponse<MemberListItem>>(ADMIN_DIRECTORY_PATH, { signal }),
    );
  } catch (cause) {
    if (signal?.aborted) throw cause;
    return null;
  }
}

/** 고르기 · 표에 쓰는 관리자 이름. 마스킹한 이름이 겹칠 수 있어 고르기에는 마스킹한 이메일을 같이 보인다 */
export function adminOptionLabel(admin: AdminEntry): string {
  return `${admin.name} (${admin.email})`;
}
