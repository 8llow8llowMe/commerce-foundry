import type { Metadata } from 'next';
import { cookies } from 'next/headers';
import { AdminAccountManager } from '@/components/admin-account/AdminAccountManager';
import { parseAdminPage } from '@/lib/admin-account/query';
import { authCookies } from '@/lib/auth-cookies';
import { memberIdOfToken } from '@/lib/auth/session-member';

export const metadata: Metadata = { title: '관리자 계정' };

/**
 * 가게 운영자 계정(#352) — 목록 · 추가 · 비활성화 · 동료 OTP 복구(#428). 페이지는 주소가 정본이다.
 * 내 아이디는 서버에서 access 쿠키의 `sub` 만 읽어 넘긴다 — 토큰은 브라우저로 보내지 않는다.
 */
export default async function AdminsPage({ searchParams }: PageProps<'/admins'>) {
  const store = await cookies();
  return (
    <AdminAccountManager
      page={parseAdminPage(await searchParams)}
      selfMemberId={memberIdOfToken(store.get(authCookies.accessName)?.value)}
    />
  );
}
