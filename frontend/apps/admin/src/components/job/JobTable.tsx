import Link from 'next/link';
import { progressText } from '@/lib/job/detail';
import { formatJobTime } from '@/lib/job/format';
import type { JobItem } from '@/lib/job/types';

/**
 * 작업 목록 표(#272 · #305). 운영자가 볼 칸만 있다 — 작업 이름(`typeName`, 상세 링크), 상태(서버 `status.name`, 설명은 title)
 * 아래에 서버 안내 문구(`notice` — 실패 이유 · 재시도 대기 · 취소 요청됨)를 그대로, 진행률, 요청 · 끝난 시각, 작업 번호.
 */
export function JobTable({ jobs }: { jobs: JobItem[] }) {
  return (
    // sr-only 같은 absolute 요소가 스크롤 상자 밖으로 나가지 않게 relative 를 준다
    <div className="relative overflow-x-auto">
      <table className="w-full min-w-[48rem] text-left text-sm">
        <caption className="sr-only">백그라운드 작업 목록</caption>
        <thead className="text-muted-fg">
          <tr>
            <th scope="col" className="py-2 pr-4 font-medium">
              작업
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              상태
            </th>
            <th scope="col" className="py-2 pr-4 text-right font-medium">
              진행률
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              요청 시각
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              끝난 시각
            </th>
            <th scope="col" className="py-2 font-medium">
              작업 번호
            </th>
          </tr>
        </thead>
        <tbody>
          {jobs.map((job) => (
            <tr key={job.jobId} className="border-t border-border align-top">
              <th scope="row" className="py-3 pr-4 font-medium">
                <Link
                  href={`/jobs/${encodeURIComponent(job.jobId)}`}
                  className="underline underline-offset-4"
                >
                  {job.typeName}
                </Link>
              </th>
              <td className="max-w-[24rem] py-3 pr-4">
                <span title={job.status.description} className="whitespace-nowrap">
                  {job.status.name}
                </span>
                {job.notice && <p className="mt-1 text-xs text-muted-fg">{job.notice}</p>}
              </td>
              <td className="py-3 pr-4 text-right tabular-nums">
                {progressText(job.progressPercent)}
              </td>
              <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                {formatJobTime(job.requestedAt)}
              </td>
              <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                {formatJobTime(job.finishedAt)}
              </td>
              <td className="py-3 whitespace-nowrap text-muted-fg tabular-nums">{job.jobId}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
