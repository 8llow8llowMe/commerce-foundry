import type { Metadata } from 'next';
import { JobDetailView } from '@/components/job/JobDetailView';

export const metadata: Metadata = { title: '백그라운드 작업' };

/**
 * 작업 상세(#272). 기능 API 가 `202 + jobId` 로 답하면 이 화면으로 와서 진행 상황을 본다.
 * `key` — 다른 작업으로 옮겨 가면 주기 갱신 · 취소 상태를 새로 시작한다.
 */
export default async function JobPage({ params }: PageProps<'/jobs/[jobId]'>) {
  const { jobId } = await params;
  return <JobDetailView key={jobId} jobId={jobId} />;
}
