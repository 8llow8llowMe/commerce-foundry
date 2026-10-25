import type { ReactNode } from 'react';
import { prettyJson, progressText } from '@/lib/job/detail';
import { formatJobTime } from '@/lib/job/format';
import type { JobDetail } from '@/lib/job/types';

/**
 * 작업 상세의 값 묶음(#272). 상태는 서버 `status.name` 과 설명을, 입력 · 결과는 JSON 원문을 들여 써서 보인다.
 * 결과 파일 링크는 #212 PR-E 뒤에 붙인다.
 */
export function JobFields({ job }: { job: JobDetail }) {
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
      </section>

      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-[10rem_1fr]">
        <Field label="작업 번호">{job.jobId}</Field>
        <Field label="종류">{job.type}</Field>
        <Field label="시도">
          {job.attempt} / {job.maxAttempts}
        </Field>
        <Field label="우선순위">{job.priority}</Field>
        <Field label="오류 코드">{job.errorCode}</Field>
        <Field label="오류 설명">{job.errorMessage}</Field>
        <Field label="예정 시각">{formatJobTime(job.scheduledAt)}</Field>
        <Field label="시작 시각">{formatJobTime(job.startedAt)}</Field>
        <Field label="종료 시각">{formatJobTime(job.finishedAt)}</Field>
        <Field label="등록한 회원">{job.requestedByMemberId ?? '시스템'}</Field>
        <Field label="취소 요청한 회원">{job.cancelRequestedByMemberId}</Field>
        <Field label="멱등 키">{job.dedupKey}</Field>
      </dl>

      <JsonBlock title="결과" raw={job.resultJson} empty="결과가 없습니다." />
      <JsonBlock title="입력" raw={job.payloadJson} empty="입력이 없습니다." />
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  const empty = children === null || children === undefined || children === '';
  return (
    <>
      <dt className="text-muted-fg">{label}</dt>
      <dd className="min-w-0 break-all">
        {empty ? <span className="text-muted-fg">—</span> : children}
      </dd>
    </>
  );
}

function JsonBlock({ title, raw, empty }: { title: string; raw: string | null; empty: string }) {
  return (
    <section className="flex flex-col gap-2">
      <h2 className="font-semibold">{title}</h2>
      {raw === null || raw === '' ? (
        <p className="text-sm text-muted-fg">{empty}</p>
      ) : (
        <pre className="max-h-96 overflow-auto rounded-md bg-muted p-4 text-xs">
          <code>{prettyJson(raw)}</code>
        </pre>
      )}
    </section>
  );
}
