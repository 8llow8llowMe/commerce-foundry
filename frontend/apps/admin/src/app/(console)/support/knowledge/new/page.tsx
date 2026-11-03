import type { Metadata } from 'next';
import { KnowledgeCreateForm } from '@/components/knowledge/KnowledgeCreateForm';

export const metadata: Metadata = { title: '새 지식 문서' };

/** 새 지식 문서(#473). 만들면 상세(`/support/knowledge/[documentId]`)로 옮긴다 */
export default function KnowledgeCreatePage() {
  return <KnowledgeCreateForm />;
}
