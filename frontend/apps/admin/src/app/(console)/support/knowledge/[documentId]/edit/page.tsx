import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { KnowledgeEditScreen } from '@/components/knowledge/KnowledgeEditScreen';

export const metadata: Metadata = { title: '지식 내용 고치기' };

/** 초안 고치기 · 새 초안 만들기(#473). 버전이 최신이어야 해서 클라이언트에서 읽는다 */
export default async function KnowledgeEditPage({
  params,
}: PageProps<'/support/knowledge/[documentId]/edit'>) {
  const { documentId } = await params;
  if (!/^\d{1,19}$/.test(documentId)) notFound();
  return <KnowledgeEditScreen documentId={documentId} />;
}
