import { cookies } from 'next/headers';
import type { ReactNode } from 'react';
import { AdminShell } from '@/components/AdminShell';
import { authCookies } from '@/lib/auth-cookies';
import { operatorRoleOfToken } from '@/lib/auth/session-member';

/**
 * 콘솔 화면은 모두 같은 화면 틀을 쓴다. 로그인 화면(`app/login`)은 이 그룹 밖이다.
 * 운영자 역할은 서버에서 access 쿠키의 `role` claim 만 읽어 넘긴다(#237) — 토큰은 브라우저로 보내지 않는다.
 */
export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const store = await cookies();
  return (
    <AdminShell role={operatorRoleOfToken(store.get(authCookies.accessName)?.value)}>
      {children}
    </AdminShell>
  );
}
