import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { KnowledgeDetailScreen } from '@/components/knowledge/KnowledgeDetailScreen';

export const metadata: Metadata = { title: '지식 상세' };

/**
 * 상담 지식 상세 · 승인 · 게시 · 보관(#473). 작업 응답으로 화면을 바로 바꿔 그려서 클라이언트에서 읽는다.
 * 숫자가 아닌 아이디는 서버 400(`SUPPORT_198`) 대신 먼저 not-found 다.
 */
export default async function KnowledgeDetailPage({
  params,
}: PageProps<'/support/knowledge/[documentId]'>) {
  const { documentId } = await params;
  if (!/^\d{1,19}$/.test(documentId)) notFound();
  return <KnowledgeDetailScreen documentId={documentId} />;
}
