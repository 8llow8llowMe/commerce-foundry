import type { Metadata } from 'next';
import { ClaimDetailScreen } from '@/components/claim/ClaimDetailScreen';

export const metadata: Metadata = { title: '클레임 상세' };

/** 어드민 클레임 상세(#30). 읽기 전용이다 */
export default async function ClaimDetailPage({ params }: PageProps<'/claims/[claimId]'>) {
  const { claimId } = await params;
  return <ClaimDetailScreen claimId={claimId} />;
}
