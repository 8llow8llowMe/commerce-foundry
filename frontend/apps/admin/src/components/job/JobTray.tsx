'use client';

import Link from 'next/link';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { HeaderMenu } from '@/components/HeaderMenu';
import { isActiveJob, POLL_FAILED_NOTICE } from '@/lib/job/detail';
import { createJobDetailController } from '@/lib/job/detail-controller';
import { noTrackedJobs, trackedJobs } from '@/lib/job/tracked-jobs';
import { JobCancelControl } from './JobCancelControl';
import { JobProgressBar } from './JobProgressBar';

type RowStatus = 'active' | 'done';

/** 상단 바 버튼 문구. 실행 중이 있으면 그 수를, 없으면 끝난 작업 수를 보인다 */
export function jobTrayLabel(active: number, total: number): string {
  return active > 0 ? `진행 중 작업 ${active}` : `끝난 작업 ${total}`;
}

/**
 * 상단 바 작업 진행 표시(#214) — 이 탭에서 시작했거나 연 작업(`trackedJobs`)의 진행률 · 취소 · 결과 이동.
 * 화면을 옮겨도 레이아웃에 남아 계속 보인다. 지켜볼 작업이 없으면 그리지 않는다.
 * 작업마다 상세(#272)의 `createJobDetailController` 를 그대로 쓴다 — 실행 중이면 3초마다 읽고 끝나면 멈추며, 숨은 탭에서는 쉰다.
 * 패널이 닫혀도 행은 DOM 에 남아(`HeaderMenu` 의 hidden) 계속 읽는다.
 */
export function JobTray() {
  const ids = useSyncExternalStore(trackedJobs.subscribe, trackedJobs.get, noTrackedJobs);
  const [statuses, setStatuses] = useState<Record<string, RowStatus>>({});

  if (ids.length === 0) return null;
  const active = ids.filter((id) => statuses[id] !== 'done').length;
  const label = jobTrayLabel(active, ids.length);

  return (
    <HeaderMenu
      title="작업 진행"
      buttonLabel={label}
      label={
        <>
          <span
            aria-hidden
            className={`size-2 rounded-full bg-primary-fg ${active > 0 ? 'animate-pulse' : 'opacity-50'}`}
          />
          <span className="hidden sm:inline">{label}</span>
          <span className="tabular-nums sm:hidden">{active > 0 ? active : ids.length}</span>
        </>
      }
    >
      {(close) => (
        <div className="flex max-h-[min(32rem,calc(100dvh-6rem))] flex-col">
          <h2 className="border-b border-border px-4 py-3 text-sm font-semibold">작업 진행</h2>
          <ul className="min-h-0 flex-1 divide-y divide-border overflow-y-auto">
            {ids.map((id) => (
              <TrackedJobRow
                key={id}
                jobId={id}
                onNavigate={close}
                onStatus={(status) =>
                  setStatuses((current) =>
                    current[id] === status ? current : { ...current, [id]: status },
                  )
                }
              />
            ))}
          </ul>
        </div>
      )}
    </HeaderMenu>
  );
}

function TrackedJobRow({
  jobId,
  onNavigate,
  onStatus,
}: {
  jobId: string;
  onNavigate: () => void;
  onStatus: (status: RowStatus) => void;
}) {
  const [controller] = useState(() => createJobDetailController(jobId));
  const { view, pollFailed, cancelling, cancelError } = useSyncExternalStore(
    controller.subscribe,
    controller.getState,
    controller.getState,
  );
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    controller.start();
    return () => controller.stop();
  }, [controller]);

  const status: RowStatus =
    view.kind === 'loading' || (view.kind === 'ready' && isActiveJob(view.job)) ? 'active' : 'done';
  useEffect(() => onStatus(status), [status, onStatus]);

  // 없는 작업(지워짐 · 손으로 고친 저장소 값)은 지켜볼 목록에서 뺀다
  useEffect(() => {
    if (view.kind === 'notFound') trackedJobs.untrack(jobId);
  }, [view.kind, jobId]);

  const href = `/jobs/${encodeURIComponent(jobId)}`;
  const remove = (
    <button
      type="button"
      onClick={() => trackedJobs.untrack(jobId)}
      className="rounded-md px-2 py-1 text-xs text-muted-fg hover:bg-muted hover:text-fg"
    >
      목록에서 빼기
    </button>
  );

  if (view.kind !== 'ready') {
    return (
      <li className="flex items-center justify-between gap-3 px-4 py-3 text-sm">
        <span className="text-muted-fg">
          {view.kind === 'loading'
            ? '불러오는 중…'
            : view.kind === 'denied'
              ? '작업 상태를 볼 수 없습니다.'
              : view.kind === 'notFound'
                ? '작업을 찾을 수 없습니다.'
                : '작업 상태를 불러오지 못했습니다.'}
        </span>
        {view.kind !== 'loading' && remove}
      </li>
    );
  }

  const { job } = view;
  const active = isActiveJob(job);
  return (
    <li className="flex flex-col gap-2 px-4 py-3">
      <div className="flex items-start justify-between gap-3">
        <Link
          href={href}
          onClick={onNavigate}
          className="min-w-0 break-words text-sm font-medium underline-offset-4 hover:underline"
        >
          {job.typeName}
        </Link>
        <span className="shrink-0 text-xs text-muted-fg">{job.status.name}</span>
      </div>
      {active && <JobProgressBar job={job} label={`${job.typeName} 진행률`} />}
      {job.progressMessage && active && (
        <p className="text-xs text-muted-fg">{job.progressMessage}</p>
      )}
      {job.notice && <p className="rounded-md bg-muted px-3 py-2 text-xs">{job.notice}</p>}
      {pollFailed && <p className="text-xs text-danger">{POLL_FAILED_NOTICE}</p>}
      <p role="status" className="text-xs text-danger empty:hidden">
        {confirming && job.cancellable ? '' : cancelError}
      </p>
      {active ? (
        <JobCancelControl
          job={job}
          confirming={confirming}
          cancelling={cancelling}
          onAsk={() => setConfirming(true)}
          onDismiss={() => setConfirming(false)}
          onConfirm={() => void controller.cancel().then(() => setConfirming(false))}
        />
      ) : (
        <div className="flex items-center justify-between gap-3">
          <Link
            href={href}
            onClick={() => {
              onNavigate();
              trackedJobs.untrack(jobId);
            }}
            className="text-sm underline underline-offset-4"
          >
            결과 보기
          </Link>
          {remove}
        </div>
      )}
    </li>
  );
}
