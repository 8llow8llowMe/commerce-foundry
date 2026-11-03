import type { Metadata } from 'next';
import { KnowledgeList } from '@/components/knowledge/KnowledgeList';
import { parseKnowledgeQuery } from '@/lib/knowledge/query';

export const metadata: Metadata = { title: '지식 관리' };

/** 상담 지식 목록(#473). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다 */
export default async function KnowledgePage({ searchParams }: PageProps<'/support/knowledge'>) {
  return <KnowledgeList query={parseKnowledgeQuery(await searchParams)} />;
}
