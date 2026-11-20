import type { Metadata } from 'next';
import { PickingWorkbench } from '@/components/picking/PickingWorkbench';

export const metadata: Metadata = { title: '출고 작업대' };

/** 출고 묶음의 피킹 목록 · 포장 검수(#364). 검수 응답으로 화면을 바로 바꿔 그려서 클라이언트에서 읽는다 */
export default async function PickingBatchPage({ params }: PageProps<'/picking/[batchId]'>) {
  const { batchId } = await params;
  return <PickingWorkbench batchId={batchId} />;
}
