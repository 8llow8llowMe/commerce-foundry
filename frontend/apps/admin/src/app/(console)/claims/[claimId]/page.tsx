import type { Metadata } from 'next';
import { cookies } from 'next/headers';
import { ClaimDetailScreen } from '@/components/claim/ClaimDetailScreen';
import { authCookies } from '@/lib/auth-cookies';
import { canManageOrders, canManageSettlement } from '@/lib/auth/permissions';
import { operatorRoleOfToken } from '@/lib/auth/session-member';

export const metadata: Metadata = { title: '클레임 상세' };

/**
 * 어드민 클레임 상세(#30). 반품의 승인 · 거절, 결제 후 취소의 다시 시도 · 환불 완료 확정 · 재고 복원 없이 종결이 있다.
 * 버튼을 거를 역할은 서버에서 access 쿠키의 `role` claim 만 읽어 넘긴다 — 토큰은 브라우저로 보내지 않는다(#237).
 */
export default async function ClaimDetailPage({ params }: PageProps<'/claims/[claimId]'>) {
  const { claimId } = await params;
  const store = await cookies();
  const role = operatorRoleOfToken(store.get(authCookies.accessName)?.value);
  return (
    <ClaimDetailScreen
      claimId={claimId}
      canSettle={canManageSettlement(role)}
      canManageOrders={canManageOrders(role)}
    />
  );
}
