import type { Metadata } from 'next';
import { MemberList } from '@/components/member/MemberList';
import { parseMemberQuery } from '@/lib/member/query';

export const metadata: Metadata = { title: '회원' };

/** 어드민 회원 목록(#241). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다 */
export default async function MembersPage({ searchParams }: PageProps<'/members'>) {
  return <MemberList query={parseMemberQuery(await searchParams)} />;
}
