import type { Metadata } from 'next';
import { ClaimList } from '@/components/claim/ClaimList';
import { parseClaimQuery } from '@/lib/claim/query';

export const metadata: Metadata = { title: '클레임' };

/** 어드민 클레임 목록(#30). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다 */
export default async function ClaimsPage({ searchParams }: PageProps<'/claims'>) {
  return <ClaimList query={parseClaimQuery(await searchParams)} />;
}
