import type { Metadata } from 'next';
import { JobList } from '@/components/job/JobList';
import { parseJobQuery } from '@/lib/job/query';

export const metadata: Metadata = { title: '백그라운드 작업' };

/** 어드민 백그라운드 작업 목록(#272). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다 */
export default async function JobsPage({ searchParams }: PageProps<'/jobs'>) {
  return <JobList query={parseJobQuery(await searchParams)} />;
}
