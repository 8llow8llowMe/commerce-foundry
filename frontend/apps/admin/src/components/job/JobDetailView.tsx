'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED } from '@/lib/api-errors';
import { JOB_NOT_FOUND_TEXT, POLL_FAILED_NOTICE } from '@/lib/job/detail';
import { createJobDetailController } from '@/lib/job/detail-controller';
import { JobCancelControl } from './JobCancelControl';
import { JobFields } from './JobFields';

/**
 * 어드민 작업 상세(#272) — `GET /api/v1/admin/jobs/{jobId}`.
 * 읽기 · 주기 갱신 · 취소는 `createJobDetailController` 가 하고 이 화면은 그 상태를 그린다
 * (실행 중이면 다시 읽고 끝나면 멈춘다, 숨은 탭에서는 쉬고, 일시 오류는 마지막 상태를 둔 채 간격을 늘려 계속 읽는다).
 * 취소에 성공하면 응답 본문(상태 · 서버 안내 · cancellable)이 곧 결과라 따로 안내하지 않고, 실패만 안내한다.
 * 페이지가 `key={jobId}` 로 붙여 작업마다 새 컨트롤러다.
 */
export function JobDetailView({ jobId }: { jobId: string }) {
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
          {view.kind === 'ready' ? view.job.typeName : '작업'}
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
      <p role="status" className="text-sm text-danger empty:hidden">
        {view.kind === 'ready' && !(confirming && view.job.cancellable) ? cancelError : ''}
      </p>

      {view.kind === 'ready' && (
        <>
          {pollFailed && <p className="text-sm text-danger">{POLL_FAILED_NOTICE}</p>}
          <JobCancelControl
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
