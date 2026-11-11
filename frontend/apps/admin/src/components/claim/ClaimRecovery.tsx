'use client';

import { apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import type { AccessDenied } from '@/lib/api-errors';
import { refundDeadlineOf, RETRY_SENT, retryFailure } from '@/lib/claim/retry';
import { adminClaimRetryPath, type ClaimDetail } from '@/lib/claim/types';
import { formatClaimTime } from './ClaimTable';

type Notice = { tone: 'info' | 'error'; message: string };

/**
 * 결제 후 취소 처리 상황 + "다시 시도"(#30 · BE #17 A2b). 반품 접수에는 그리지 않는다(다시 시도 대상이 아니다).
 * 버튼은 서버 `retryable` 일 때만 두고, 한 번 더 확인한 뒤 보낸다. 보내는 중에는 다시 누를 수 없다.
 * 성공 · 실패 문구는 이 칸에 둔다 — 상세를 다시 읽어도 이 칸은 그대로라 문구가 남는다.
 */
export function ClaimRecovery({
  claim,
  onUpdated,
  onReload,
  now,
}: {
  claim: ClaimDetail;
  /** 다시 시도 응답(현재 상세)으로 화면을 바꾼다 */
  onUpdated: (claim: ClaimDetail) => void;
  /** 서버 상태가 화면과 다를 수 있을 때 상세를 다시 읽는다(화면은 그대로 둔 채) */
  onReload: () => void;
  /** 결제 취소 기한 계산 기준 — 테스트에서 고정한다. 없으면 이 칸을 처음 그린 시각이다(남은 시간은 참고용이라 매번 갱신하지 않는다) */
  now?: number;
}) {
  const [shownAt] = useState(() => Date.now());
  const [confirming, setConfirming] = useState(false);
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  /** 겹친 요청 막기 — 상태 반영 전 연타도 막으려고 ref 로 둔다 */
  const inFlight = useRef(false);
  // 누른 버튼이 사라지는 전환 뒤 포커스를 둘 `data-focus` 키(AdminAccountManager 와 같은 방식)
  const pendingFocus = useRef<string | null>(null);
  const rootRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const key = pendingFocus.current;
    if (!key) return;
    pendingFocus.current = null;
    const root = rootRef.current;
    // 옮길 곳이 사라졌으면(취소 뒤 retryable=false 등) 문구로 옮긴다
    (
      root?.querySelector<HTMLElement>(`[data-focus="${key}"]`) ??
      root?.querySelector<HTMLElement>('[data-focus="notice"]')
    )?.focus();
  });

  if (claim.type.code !== 'CANCELLATION') return null;

  const { recovery } = claim;
  const deadline = refundDeadlineOf(claim, now ?? shownAt);

  const send = () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setSending(true);
    setNotice(null);
    setDenied(null);
    apiFetch<ClaimDetail>(adminClaimRetryPath(claim.claimId), {
      method: 'POST',
      json: { expectedVersion: recovery.version },
    })
      .then(
        (updated) => {
          setNotice({ tone: 'info', message: RETRY_SENT });
          pendingFocus.current = 'notice';
          onUpdated(updated);
        },
        (cause: unknown) => {
          const failure = retryFailure(cause);
          if (failure.kind === 'access') {
            setDenied(failure.denied);
            pendingFocus.current = 'retry';
            return;
          }
          setNotice({ tone: 'error', message: failure.message });
          // 결과 문구로 옮긴다 — 다시 읽은 상세가 버튼을 거둬도(retryable=false) 포커스가 body 로 빠지지 않게
          pendingFocus.current = 'notice';
          if (failure.reload) onReload();
        },
      )
      .finally(() => {
        inFlight.current = false;
        setSending(false);
        setConfirming(false);
      });
  };

  return (
    <section
      ref={rootRef}
      aria-labelledby="claim-recovery"
      className="flex flex-col gap-3 rounded-md border border-border p-4"
    >
      <h2 id="claim-recovery" className="font-medium">
        처리 상황
      </h2>
      <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-muted-fg">상황</dt>
        <dd>
          <p className="font-medium">{recovery.situation.name}</p>
          <p className="text-muted-fg">{recovery.situation.description}</p>
        </dd>
        {recovery.nextAutomaticAttemptAt && (
          <>
            <dt className="text-muted-fg">다음 자동 확인</dt>
            <dd>{formatClaimTime(recovery.nextAutomaticAttemptAt)}</dd>
          </>
        )}
        {deadline && (
          <>
            <dt className="text-muted-fg">결제 취소 기한</dt>
            <dd>
              {formatClaimTime(deadline.at.toISOString())} (접수 + 14일) ·{' '}
              {deadline.remaining ?? '지났습니다'}
            </dd>
          </>
        )}
        <dt className="text-muted-fg">다시 시도</dt>
        <dd>{recovery.retryable ? '할 수 있습니다' : '할 수 없습니다'}</dd>
      </dl>

      {recovery.retryable &&
        (confirming ? (
          <div className="flex flex-col gap-3 rounded-md bg-muted p-3">
            <p className="text-sm">
              같은 접수 · 같은 금액으로 결제 취소와 재고 복원을 다시 보냅니다. 다시 시도할까요?
            </p>
            <div className="flex flex-wrap items-center gap-3">
              {/* 보내는 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 send 가 막는다 */}
              <Button data-focus="confirm" aria-disabled={sending || undefined} onClick={send}>
                {sending ? '보내는 중…' : '다시 시도 확인'}
              </Button>
              <Button
                variant="secondary"
                aria-disabled={sending || undefined}
                onClick={() => {
                  if (inFlight.current) return;
                  setConfirming(false);
                  pendingFocus.current = 'retry';
                }}
              >
                취소
              </Button>
            </div>
          </div>
        ) : (
          <div>
            <Button
              variant="secondary"
              data-focus="retry"
              onClick={() => {
                setConfirming(true);
                setNotice(null);
                setDenied(null);
                pendingFocus.current = 'confirm';
              }}
            >
              다시 시도
            </Button>
          </div>
        ))}

      {notice && (
        <p
          role={notice.tone === 'error' ? 'alert' : 'status'}
          tabIndex={-1}
          data-focus="notice"
          className={notice.tone === 'error' ? 'text-sm text-danger' : 'text-sm'}
        >
          {notice.message}
        </p>
      )}
      {denied && <AccessNotice denied={denied} />}
    </section>
  );
}
