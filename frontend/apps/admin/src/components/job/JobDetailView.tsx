'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED } from '@/lib/api-errors';
import {
  canCancel,
  isCancelPending,
  JOB_NOT_FOUND_TEXT,
  POLL_FAILED_NOTICE,
} from '@/lib/job/detail';
import { createJobDetailController } from '@/lib/job/detail-controller';
import type { JobDetail } from '@/lib/job/types';
import { JobFields } from './JobFields';

/**
 * 어드민 작업 상세(#272) — `GET /api/v1/admin/jobs/{jobId}`.
 * 읽기 · 주기 갱신 · 취소는 `createJobDetailController` 가 하고 이 화면은 그 상태를 그린다
 * (실행 중이면 다시 읽고 끝나면 멈춘다, 숨은 탭에서는 쉬고, 일시 오류는 마지막 상태를 둔 채 간격을 늘려 계속 읽는다).
 * 페이지가 `key={jobId}` 로 붙여 작업마다 새 컨트롤러다.
 */
export function JobDetailView({ jobId }: { jobId: string }) {
  const [controller] = useState(() => createJobDetailController(jobId));
  const { view, pollFailed, cancelling, notice } = useSyncExternalStore(
    controller.subscribe,
    controller.getState,
    controller.getState,
  );
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    controller.start();
    return () => controller.stop();
  }, [controller]);

  const cancel = async () => {
    await controller.cancel();
    setConfirming(false);
  };

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href="/jobs" className="text-sm text-muted-fg underline underline-offset-4">
          작업 목록
        </Link>
        <h1 className="text-xl font-semibold">
          {view.kind === 'ready' ? `${view.job.type} #${view.job.jobId}` : '작업'}
        </h1>
      </div>

      {view.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {view.kind === 'denied' && <AccessNotice denied={view.denied} />}
      {view.kind === 'notFound' && <p className="text-sm text-muted-fg">{JOB_NOT_FOUND_TEXT}</p>}
      {view.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={controller.reload}>
            다시 불러오기
          </Button>
        </div>
      )}

      {/* live region 은 늘 두고 내용만 바꾼다 — 처음 나타날 때도 읽히게. 확인 단계에서는 이전 안내를 숨긴다 */}
      <p role="status" className="text-sm empty:hidden">
        {view.kind === 'ready' && !(confirming && canCancel(view.job)) ? notice : ''}
      </p>

      {view.kind === 'ready' && (
        <>
          {pollFailed && <p className="text-sm text-danger">{POLL_FAILED_NOTICE}</p>}
          <CancelControl
            job={view.job}
            confirming={confirming}
            cancelling={cancelling}
            onAsk={() => setConfirming(true)}
            onDismiss={() => setConfirming(false)}
            onConfirm={() => void cancel()}
          />
          <JobFields job={view.job} />
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
