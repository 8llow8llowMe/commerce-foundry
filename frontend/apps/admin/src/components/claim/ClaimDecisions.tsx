'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import {
  DECISION_DONE,
  DECISION_LABEL,
  DECISION_OWNER_ONLY,
  DECISION_REASON_MAX,
  decisionActions,
  decisionFailure,
  decisionNeedsOwner,
  decisionPath,
  decisionReasonError,
  normalizeDecisionReason,
  type DecisionKind,
} from '@/lib/claim/decision';
import type { ClaimDetail } from '@/lib/claim/types';

type Notice = { tone: 'info' | 'error'; message: string };

/**
 * 확인 단계 설명 — 결제사를 부르지 않는다는 것을 먼저 말한다. 실제 환불 확인 · 재취소 금지의 대상은 PG 상점관리자(결제사 콘솔)이고,
 * 우리 어드민 화면은 "결제 확인 화면" 으로만 부른다(`backend/docs/payment-guide.md` 수동 환불 확정 절).
 * 확정은 커밋 뒤 주문 취소 완료 · 재고 복원으로 이어지고, 종결만 원격 호출이 없다.
 */
const CONFIRM_COPY: Record<DecisionKind, { title: string; lines: string[] }> = {
  refundConfirmation: {
    title: '결제사에 환불을 요청하지 않습니다',
    lines: [
      'PG 상점관리자(결제사 콘솔)에서 고객에게 실제로 환불된 것을 확인한 뒤에만 확정하세요. 이 버튼은 돈을 돌려주지 않습니다.',
      '확정하면 이 클레임을 환불 완료로 기록하고, 주문이 취소 요청이면 취소 완료로 바꾼 뒤 재고 복원을 이어 갑니다.',
      '결제 확인 화면의 환불 실패 표시는 바뀌지 않습니다. PG 상점관리자에서 같은 건을 다시 취소하지 마세요 — 두 번 환불될 수 있습니다.',
    ],
  },
  completion: {
    title: '재고를 복원하지 않고 이 클레임을 닫습니다',
    lines: [
      '재고를 조정하기 전에 재고 이력에서 이 주문의 재고 복원이 이미 들어갔는지 먼저 확인하세요. 이미 복원됐으면 조정하지 않고 종결합니다 — 그대로 조정하면 재고가 두 번 늘어납니다.',
      '복원되지 않았으면 재고 조정 화면에서 재고를 맞춘 뒤 종결하세요. 종결은 결제사 · 재고 서비스에 아무것도 요청하지 않습니다.',
      '종결하면 이 클레임은 완료가 되고 재고 복원을 다시 시도하지 않습니다.',
    ],
  },
};

/**
 * 결제 후 취소의 운영자 결정 — 수동 환불 확정 · 재고 복원 없이 종결(#30 · BE #17 A3). 처리 상황 칸(`ClaimRecovery`) 안에 그린다.
 * 버튼은 서버 플래그(`refundConfirmable` · `completable`)가 true 이고 대표 관리자(`canSettle`)일 때만 둔다. 역할을 모르면(null) 두고 서버 403 에 맡긴다.
 * 확인 단계에서 사유(필수)를 받고, 보내는 중에는 다시 보낼 수 없다(ref 로 연타도 막는다). 409 · 결과 모름은 상세를 다시 읽는다.
 */
export function ClaimDecisions({
  claim,
  canSettle,
  onUpdated,
  onReload,
}: {
  claim: ClaimDetail;
  /** 정산 쓰기 권한(`canManageSettlement`). null 은 역할을 모름 */
  canSettle: boolean | null;
  onUpdated: (claim: ClaimDetail) => void;
  onReload: () => void;
}) {
  const id = useId();
  const [confirming, setConfirming] = useState<DecisionKind | null>(null);
  /** 결정별 입력 — 실패로 확인 단계를 닫아도 다시 열면 남아 있다 */
  const [drafts, setDrafts] = useState<Partial<Record<DecisionKind, string>>>({});
  const [reasonError, setReasonError] = useState<string | null>(null);
  /** 확인 단계 전체 오류(쓰기 401 등) — 사유 칸이 틀린 것이 아니라 칸에 붙이지 않는다 */
  const [formError, setFormError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [denied, setDenied] = useState<'forbidden' | null>(null);
  /** 겹친 요청 막기 — 상태 반영 전 연타도 막으려고 ref 로 둔다 */
  const inFlight = useRef(false);
  /** 전환 뒤 포커스를 둘 `data-focus` 키(ClaimRecovery 와 같은 방식) */
  const pendingFocus = useRef<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const key = pendingFocus.current;
    if (!key) return;
    pendingFocus.current = null;
    const root = rootRef.current;
    // 옮길 곳이 사라졌으면(다시 읽은 상세가 버튼을 거둠) 결과 문구로 옮긴다
    (
      root?.querySelector<HTMLElement>(`[data-focus="${key}"]`) ??
      root?.querySelector<HTMLElement>('[data-focus="decision-notice"]')
    )?.focus();
  });

  const actions = decisionActions(claim, canSettle);
  const needsOwner = decisionNeedsOwner(claim, canSettle);
  // 다시 읽은 상세에서 플래그가 꺼졌으면 확인 단계도 그리지 않는다
  const open = confirming && actions.includes(confirming) ? confirming : null;
  if (actions.length === 0 && !needsOwner && !notice && !denied) return null;

  const reasonId = `${id}-reason`;
  const hintId = `${id}-reason-hint`;
  const errorId = `${id}-reason-error`;

  const send = (kind: DecisionKind) => {
    if (inFlight.current) return;
    const draft = drafts[kind] ?? '';
    const invalid = decisionReasonError(draft);
    if (invalid) {
      setReasonError(invalid);
      pendingFocus.current = 'decision-reason';
      return;
    }
    inFlight.current = true;
    setSending(true);
    setNotice(null);
    setDenied(null);
    setReasonError(null);
    setFormError(null);
    apiFetch<ClaimDetail>(decisionPath(kind, claim.claimId), {
      method: 'POST',
      json: { expectedVersion: claim.recovery.version, reason: normalizeDecisionReason(draft) },
    })
      .then(
        (updated) => {
          setNotice({ tone: 'info', message: DECISION_DONE[kind] });
          setDrafts((current) => ({ ...current, [kind]: '' }));
          setConfirming(null);
          pendingFocus.current = 'decision-notice';
          onUpdated(updated);
        },
        (cause: unknown) => {
          const failure = decisionFailure(cause);
          if (failure.kind === 'reason') {
            // 확인 단계와 입력은 그대로 — 고쳐서 다시 보낸다
            setReasonError(failure.message);
            pendingFocus.current = 'decision-reason';
            return;
          }
          if (failure.kind === 'form') {
            // 확인 단계와 입력은 그대로 — 다시 로그인한 뒤 다시 누른다
            setFormError(failure.message);
            pendingFocus.current = 'decision-form-error';
            return;
          }
          setConfirming(null);
          if (failure.kind === 'access') {
            setDenied(failure.denied);
            pendingFocus.current = `decision-${kind}`;
            return;
          }
          setNotice({ tone: 'error', message: failure.message });
          pendingFocus.current = 'decision-notice';
          if (failure.reload) onReload();
        },
      )
      .finally(() => {
        inFlight.current = false;
        setSending(false);
      });
  };

  return (
    <div
      ref={rootRef}
      data-tour="claim-decisions"
      className="flex flex-col gap-3 border-t border-border pt-3"
    >
      <h3 className="text-sm font-medium">운영자 확정 (대표 관리자)</h3>
      {needsOwner && <p className="text-sm text-muted-fg">{DECISION_OWNER_ONLY}</p>}

      {actions.length > 0 && !open && (
        <div className="flex flex-wrap gap-3">
          {actions.map((kind) => (
            <Button
              key={kind}
              variant="secondary"
              data-focus={`decision-${kind}`}
              onClick={() => {
                setConfirming(kind);
                setReasonError(null);
                setFormError(null);
                setNotice(null);
                setDenied(null);
                pendingFocus.current = 'decision-reason';
              }}
            >
              {DECISION_LABEL[kind]}
            </Button>
          ))}
        </div>
      )}

      {open && (
        <div
          role="group"
          aria-labelledby={`${id}-title`}
          className="flex flex-col gap-3 rounded-md bg-muted p-3"
        >
          <p id={`${id}-title`} className="text-sm font-medium">
            {DECISION_LABEL[open]} — {CONFIRM_COPY[open].title}
          </p>
          <ul className="flex list-disc flex-col gap-1 pl-5 text-sm">
            {CONFIRM_COPY[open].lines.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <div className="flex flex-col gap-1">
            <label htmlFor={reasonId} className="text-sm font-medium">
              처리 사유 (필수)
            </label>
            <input
              id={reasonId}
              data-focus="decision-reason"
              value={drafts[open] ?? ''}
              maxLength={DECISION_REASON_MAX}
              readOnly={sending}
              required
              aria-required="true"
              aria-invalid={reasonError ? true : undefined}
              aria-describedby={reasonError ? `${hintId} ${errorId}` : hintId}
              onChange={(event) => {
                const value = event.currentTarget.value;
                setDrafts((current) => ({ ...current, [open]: value }));
              }}
              onKeyDown={(event) => {
                // 한글 조합 중 Enter 는 글자 확정이다 — 보내지 않는다
                if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  send(open);
                }
              }}
              className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
            />
            <p id={hintId} className="text-xs text-muted-fg">
              예: PG 상점관리자에서 취소 완료 확인. {DECISION_REASON_MAX}자 이하. 클레임 상세를 볼
              수 있는 운영자에게 보이니 고객 개인정보 · 계좌번호는 적지 마세요.
            </p>
            <FieldError id={errorId} messages={reasonError ? [reasonError] : []} />
          </div>
          {formError && (
            <p
              role="alert"
              tabIndex={-1}
              data-focus="decision-form-error"
              className="text-sm text-danger"
            >
              {formError}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-3">
            {/* 보내는 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 send 가 막는다 */}
            <Button aria-disabled={sending || undefined} onClick={() => send(open)}>
              {sending ? '보내는 중…' : `${DECISION_LABEL[open]} 확인`}
            </Button>
            <Button
              variant="secondary"
              aria-disabled={sending || undefined}
              onClick={() => {
                if (inFlight.current) return;
                setConfirming(null);
                setReasonError(null);
                setFormError(null);
                pendingFocus.current = `decision-${open}`;
              }}
            >
              취소
            </Button>
          </div>
        </div>
      )}

      {notice && (
        <p
          role={notice.tone === 'error' ? 'alert' : 'status'}
          tabIndex={-1}
          data-focus="decision-notice"
          className={notice.tone === 'error' ? 'text-sm text-danger' : 'text-sm'}
        >
          {notice.message}
        </p>
      )}
      {denied && <AccessNotice denied={denied} />}
    </div>
  );
}
