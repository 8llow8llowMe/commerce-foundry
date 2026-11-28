import type { Metadata } from 'next';
import { cookies } from 'next/headers';
import { ClaimList } from '@/components/claim/ClaimList';
import { authCookies } from '@/lib/auth-cookies';
import { canAccessPath, permissionsOf } from '@/lib/auth/permissions';
import { operatorRoleOfToken } from '@/lib/auth/session-member';
import { parseClaimQuery } from '@/lib/claim/query';

export const metadata: Metadata = { title: '클레임' };

/**
 * 어드민 클레임 목록(#30). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다.
 * "결제 확인 중" 바로가기를 거를 역할은 서버에서 access 쿠키의 `role` claim 만 읽어 넘긴다(#237). 역할을 모르면 숨기지 않는다.
 */
export default async function ClaimsPage({ searchParams }: PageProps<'/claims'>) {
  const store = await cookies();
  const role = operatorRoleOfToken(store.get(authCookies.accessName)?.value);
  return (
    <ClaimList
      query={parseClaimQuery(await searchParams)}
      showPayments={canAccessPath(permissionsOf(role), '/payments')}
    />
  );
}
