'use client';

import { apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import {
  cancelFailure,
  cancelJobApiPath,
  cancelNotice,
  canCancel,
  isActiveJob,
  isCancelPending,
  isJobId,
  JOB_NOT_FOUND_TEXT,
  JOB_POLL_INTERVAL_MS,
  jobApiPath,
  loadFailure,
  POLL_FAILED_NOTICE,
} from '@/lib/job/detail';
import type { JobDetail } from '@/lib/job/types';
import { JobFields } from './JobFields';

type View =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'failed' }
  | { kind: 'ready'; job: JobDetail };

/**
 * 어드민 작업 상세(#272) — `GET /api/v1/admin/jobs/{jobId}`.
 * 실행 중(QUEUED · RUNNING)이면 `JOB_POLL_INTERVAL_MS` 마다 다시 읽고, 끝나면(SUCCEEDED · FAILED · CANCELLED) 멈춘다.
 * 다시 읽다 일시 오류가 나면 마지막 상태를 둔 채 안내하고 계속 시도한다. 취소는 `POST .../cancel` 이다.
 */
export function JobDetailView({ jobId }: { jobId: string }) {
  const valid = isJobId(jobId);
  const [view, setView] = useState<View>({ kind: 'loading' });
  /** 읽을 때마다 하나 올린다 — 주기 갱신 · 다시 불러오기 · 취소 뒤 다시 읽기 */
  const [tick, setTick] = useState(0);
  const [pollFailed, setPollFailed] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [notice, setNotice] = useState('');
  /** 응답 콜백에서 "이미 보인 작업이 있는지" 를 본다 — 있으면 일시 오류에도 화면을 지우지 않고 안내만 한다 */
  const shown = useRef(false);

  // 상태는 응답 콜백에서만 바꾼다. tick 이 바뀌면 이전 응답 · 예약한 다음 읽기를 버린다(cleanup)
  useEffect(() => {
    if (!valid) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const next = () => {
      timer = setTimeout(() => setTick((n) => n + 1), JOB_POLL_INTERVAL_MS);
    };
    apiFetch<JobDetail>(jobApiPath(jobId)).then(
      (job) => {
        if (!alive) return;
        shown.current = true;
        setView({ kind: 'ready', job });
        setPollFailed(false);
        if (isActiveJob(job)) next();
      },
      (cause: unknown) => {
        if (!alive) return;
        const failure = loadFailure(cause);
        if (failure.kind === 'failed' && shown.current) {
          setPollFailed(true);
          next();
          return;
        }
        shown.current = false;
        setView(failure);
      },
    );
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [jobId, valid, tick]);

  const reload = () => setTick((n) => n + 1);

  const cancel = async () => {
    if (cancelling) return;
    setCancelling(true);
    setNotice('');
    try {
      const job = await apiFetch<JobDetail>(cancelJobApiPath(jobId), { method: 'POST' });
      setView({ kind: 'ready', job });
      setNotice(cancelNotice(job));
      // 취소 전에 보낸 읽기가 늦게 와서 덮어쓰지 않게 다시 읽는다(주기도 새로 시작한다)
      reload();
    } catch (cause) {
      const failure = cancelFailure(cause);
      if (failure.kind === 'denied') setView({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'notFound') {
        shown.current = false;
        setView({ kind: 'notFound' });
      } else {
        setNotice(failure.notice);
        if (failure.kind === 'refresh') reload();
      }
    } finally {
      setCancelling(false);
      setConfirming(false);
    }
  };

  const current: View = valid ? view : { kind: 'notFound' };

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href="/jobs" className="text-sm text-muted-fg underline underline-offset-4">
          작업 목록
        </Link>
        <h1 className="text-xl font-semibold">
          {current.kind === 'ready' ? `${current.job.type} #${current.job.jobId}` : '작업'}
        </h1>
      </div>

      {current.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current.kind === 'denied' && <AccessNotice denied={current.denied} />}
      {current.kind === 'notFound' && <p className="text-sm text-muted-fg">{JOB_NOT_FOUND_TEXT}</p>}
      {current.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button
            variant="secondary"
            onClick={() => {
              setView({ kind: 'loading' });
              reload();
            }}
          >
            다시 불러오기
          </Button>
        </div>
      )}

      {/* live region 은 늘 두고 내용만 바꾼다 — 처음 나타날 때도 읽히게 */}
      <p role="status" className="text-sm empty:hidden">
        {current.kind === 'ready' ? notice : ''}
      </p>

      {current.kind === 'ready' && (
        <>
          {pollFailed && <p className="text-sm text-danger">{POLL_FAILED_NOTICE}</p>}
          <CancelControl
            job={current.job}
            confirming={confirming}
            cancelling={cancelling}
            onAsk={() => {
              setNotice('');
              setConfirming(true);
            }}
            onDismiss={() => setConfirming(false)}
            onConfirm={() => void cancel()}
          />
          <JobFields job={current.job} />
        </>
      )}
    </div>
  );
}

function CancelControl({
  job,
  confirming,
  cancelling,
  onAsk,
  onDismiss,
  onConfirm,
}: {
  job: JobDetail;
  confirming: boolean;
  cancelling: boolean;
  onAsk: () => void;
  onDismiss: () => void;
  onConfirm: () => void;
}) {
  if (isCancelPending(job)) {
    return (
      <p className="rounded-md bg-muted px-4 py-3 text-sm">
        취소 요청됨 — 실행 중인 작업이 다음 진행 보고에서 멈춥니다. 그 전에 끝나면 그 결과로
        끝납니다.
      </p>
    );
  }
  if (!canCancel(job)) return null;
  if (!confirming) {
    return (
      <Button variant="secondary" className="self-start" onClick={onAsk}>
        작업 취소
      </Button>
    );
  }
  return (
    <div className="flex flex-col gap-3 rounded-md bg-muted px-4 py-3">
      <p className="text-sm">
        이 작업을 취소할까요? 대기 중이면 바로 취소되고, 실행 중이면 다음 진행 보고에서 멈춥니다.
        되돌릴 수 없습니다.
      </p>
      <div className="flex gap-2">
        <Button disabled={cancelling} onClick={onConfirm}>
          {cancelling ? '취소하는 중…' : '취소 확인'}
        </Button>
        <Button variant="secondary" disabled={cancelling} onClick={onDismiss}>
          그만두기
        </Button>
      </div>
    </div>
  );
}
