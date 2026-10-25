import type { ReactNode } from 'react';
import { progressText } from '@/lib/job/detail';
import { formatJobTime } from '@/lib/job/format';
import type { JobItem } from '@/lib/job/types';

/**
 * 작업 상세의 값 묶음(#272 · #305). 운영자가 볼 칸만 있다 — 상태(서버 `status.name` · 설명), 진행률 · 진행 문구,
 * 서버 안내 문구(`notice`, 실패 이유 · 재시도 대기 · 취소 요청됨을 서버가 만든 문장 그대로), 요청 · 끝난 시각.
 */
export function JobFields({ job }: { job: JobItem }) {
  return (
    <div className="flex flex-col gap-6">
      <section aria-labelledby="job-progress" className="flex flex-col gap-2">
        <h2 id="job-progress" className="font-semibold">
          진행
        </h2>
        <p>
          <span className="font-medium">{job.status.name}</span>
          <span className="ml-2 text-sm text-muted-fg">{job.status.description}</span>
        </p>
        <div className="flex items-center gap-3">
          <div
            role="progressbar"
            aria-label="진행률"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={job.progressPercent ?? undefined}
            className="h-2 w-full max-w-sm overflow-hidden rounded-full bg-muted"
          >
            <div
              className="h-full bg-primary"
              style={{ width: `${Math.min(100, Math.max(0, job.progressPercent ?? 0))}%` }}
            />
          </div>
          <span className="text-sm tabular-nums">{progressText(job.progressPercent)}</span>
        </div>
        {job.progressMessage && <p className="text-sm">{job.progressMessage}</p>}
        {/* 서버 안내는 상태가 바뀌면 같이 바뀐다 — 주기 갱신 중 바뀐 문장이 읽히게 live region 으로 둔다 */}
        <p role="status" className="rounded-md bg-muted px-4 py-3 text-sm empty:hidden">
          {job.notice ?? ''}
        </p>
      </section>

      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-[10rem_1fr]">
        <Field label="작업 번호">{job.jobId}</Field>
        <Field label="작업">{job.typeName}</Field>
        <Field label="요청 시각">{formatJobTime(job.requestedAt)}</Field>
        <Field label="끝난 시각">{formatJobTime(job.finishedAt)}</Field>
      </dl>
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted-fg">{label}</dt>
      <dd className="min-w-0 break-all">{children}</dd>
    </>
  );
}
