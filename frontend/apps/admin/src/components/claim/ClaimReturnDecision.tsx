'use client';

import { apiFetch, formatMoney, type Money } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import {
  DECISION_REASON_MAX,
  decisionReasonError,
  normalizeDecisionReason,
} from '@/lib/claim/decision';
import {
  approvalOutcome,
  approvalPlan,
  confirmStillValid,
  deductionUnitLabel,
  failureTransition,
  faultOptions,
  initialFault,
  policyRefundOf,
  REJECT_DONE,
  RETURN_ACTION_LABEL,
  RETURN_MANAGER_ONLY,
  returnActionPath,
  returnFailure,
  returnPanelMode,
  type ConfirmSnapshot,
  type ReturnAction,
  type ReturnApprovalRequest,
  type ReturnFieldKey,
} from '@/lib/claim/return-decision';
import type { ClaimDetail, ReturnFaultCode } from '@/lib/claim/types';
import { formatClaimTime, refundLabel } from './ClaimTable';

type Notice = { tone: 'info' | 'error'; message: string };
type FieldKey = ReturnFieldKey;

/**
 * 반품 결정 칸(#30 · BE #17 B2c-2) — 접수 반품의 승인 · 거절, 승인된 반품의 승인 내용, 거절된 반품의 거절 사유.
 * 승인 입력은 귀책 · 재고로 되돌릴지(서버 필수라 고르기 전엔 승인하지 않는다) · 차감액이고, 칸의 중심은 "정책 환불액 − 차감액 = 환불 예정액" 내역이다.
 * 버튼은 서버 `approvable` · `rejectable` 과 주문 관리 권한(`canManage`)으로 고른다. 역할을 모르면(null) 두고 서버 403 에 맡긴다.
 * 승인 · 거절 모두 인라인 확인 단계를 거치고, 보내는 중에는 다시 보낼 수 없다(ref 로 연타도 막는다). 409 · 결과 모름은 상세를 다시 읽는다.
 */
export function ClaimReturnDecision({
  claim,
  canManage,
  onUpdated,
  onReload,
}: {
  claim: ClaimDetail;
  /** 주문 쓰기 권한(`canManageOrders`). null 은 역할을 모름 */
  canManage: boolean | null;
  onUpdated: (claim: ClaimDetail) => void;
  onReload: () => void;
}) {
  const id = useId();
  const decision = claim.type.code === 'RETURN' ? claim.returnDecision : null;
  const [fault, setFault] = useState<ReturnFaultCode | null>(() =>
    decision ? initialFault(decision) : null,
  );
  const [restock, setRestock] = useState<boolean | null>(null);
  const [deductionText, setDeductionText] = useState('0');
  const [rejectReason, setRejectReason] = useState('');
  /**
   * 열린 확인 단계와 그때 저장한 것 — 승인은 운영자가 확인한 본문, 거절은 화면 버전을 그대로 보낸다.
   * null 이면 확인 단계가 닫혀 있다.
   */
  const [snapshot, setSnapshot] = useState<ConfirmSnapshot | null>(null);
  /** 승인을 한 번 눌러 본 뒤에만 고르지 않은 칸을 오류로 보인다 */
  const [attempted, setAttempted] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldKey, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [denied, setDenied] = useState<'forbidden' | null>(null);
  const inFlight = useRef(false);
  const pendingFocus = useRef<string | null>(null);
  const rootRef = useRef<HTMLElement>(null);

  const mode = returnPanelMode(claim, canManage);
  const snapshotValid = snapshot !== null && confirmStillValid(claim, mode, snapshot);
  // 확인 단계 동안 상세를 다시 읽어 버전 · 금액이 바뀌었거나 버튼이 사라졌으면 확인 단계를 닫고 스냅숏을 비운다.
  // 보내는 중에는 두지 않는다 — 응답이 상태를 정리한다. (렌더 중 조건부 갱신 — React "prop 이 바뀔 때 state 맞추기")
  if (snapshot !== null && !snapshotValid && !sending) setSnapshot(null);

  useEffect(() => {
    const key = pendingFocus.current;
    if (!key) return;
    pendingFocus.current = null;
    const root = rootRef.current;
    (
      root?.querySelector<HTMLElement>(`[data-focus="${key}"]`) ??
      root?.querySelector<HTMLElement>('[data-focus="return-notice"]')
    )?.focus();
  });

  if (claim.type.code !== 'RETURN') return null;
  const approval = claim.returnApproval;
  const rejection =
    claim.status.code === 'REJECTED' && claim.recovery.lastDecision
      ? claim.recovery.lastDecision
      : null;
  if (!decision && !approval && !rejection && !notice && !denied) return null;

  // 다시 읽은 상세에서 고른 귀책이 사라졌으면 서버 기본 귀책으로 돌아간다
  const chosenFault: ReturnFaultCode | null = !decision
    ? null
    : fault && policyRefundOf(decision, fault)
      ? fault
      : initialFault(decision);
  const plan = approvalPlan({ claim, fault: chosenFault, restock, deductionText });
  // 낡은 스냅숏(위에서 비운다)으로는 확인 단계를 그리지 않는다. 보내는 중에는 연 채로 둔다
  const live = snapshot && (snapshotValid || sending) ? snapshot : null;
  const open = live?.action ?? null;
  const pendingApproval = live?.action === 'approve' ? live.request : null;

  const options = decision ? faultOptions(decision) : [];
  const faultNameOf = (code: ReturnFaultCode | null) =>
    options.find((option) => option.code === code)?.fault.name ?? null;
  const blocker = plan.blocker;

  /** 칸 아래 오류 — 서버 거절 → 입력 형식 → (승인을 눌러 본 뒤) 고르지 않은 칸. 0원 이하는 내역 아래에 따로 보인다 */
  const errorOf = (field: FieldKey): string | null => {
    const server = fieldErrors[field];
    if (server) return server;
    if (field === 'deduction' && plan.deductionError) return plan.deductionError;
    if (attempted && blocker && !blocker.notPayable && blocker.field === field)
      return blocker.message;
    return null;
  };
  const messagesOf = (field: FieldKey) => {
    const message = errorOf(field);
    return message ? [message] : [];
  };

  const resetMessages = () => {
    setFieldErrors({});
    setFormError(null);
    setNotice(null);
    setDenied(null);
  };

  const startApprove = () => {
    if (inFlight.current) return;
    resetMessages();
    setAttempted(true);
    if (plan.blocker || !plan.request) {
      pendingFocus.current = plan.blocker?.field
        ? `return-${plan.blocker.field}`
        : 'return-blocker';
      return;
    }
    setSnapshot({ action: 'approve', request: plan.request });
    pendingFocus.current = 'return-confirm-approve';
  };

  const startReject = () => {
    if (inFlight.current) return;
    resetMessages();
    setSnapshot({ action: 'reject', expectedVersion: claim.recovery.version });
    pendingFocus.current = 'return-reason';
  };

  const send = (action: ReturnAction) => {
    if (inFlight.current || !live || live.action !== action) return;
    let json: ReturnApprovalRequest | { expectedVersion: number; reason: string };
    if (live.action === 'approve') {
      json = live.request;
    } else {
      const invalid = decisionReasonError(rejectReason);
      if (invalid) {
        setFieldErrors({ reason: invalid });
        pendingFocus.current = 'return-reason';
        return;
      }
      // 확인 단계를 열 때 저장한 화면 버전을 보낸다(승인의 스냅숏과 같은 방식)
      json = {
        expectedVersion: live.expectedVersion,
        reason: normalizeDecisionReason(rejectReason),
      };
    }
    inFlight.current = true;
    setSending(true);
    resetMessages();
    apiFetch<ClaimDetail>(returnActionPath(action, claim.claimId), { method: 'POST', json })
      .then(
        (updated) => {
          setNotice(
            action === 'approve'
              ? approvalOutcome(updated)
              : { tone: 'info', message: REJECT_DONE },
          );
          setSnapshot(null);
          setRejectReason('');
          pendingFocus.current = 'return-notice';
          onUpdated(updated);
        },
        (cause: unknown) => {
          const next = failureTransition(returnFailure(cause, action), action);
          if (next.closeConfirm || !next.keepSnapshot) setSnapshot(null);
          setFieldErrors(next.fieldErrors);
          setFormError(next.formError);
          setNotice(next.notice);
          setDenied(next.denied);
          pendingFocus.current = next.focus;
          if (next.reload) onReload();
        },
      )
      .finally(() => {
        inFlight.current = false;
        setSending(false);
      });
  };

  const cancelConfirm = (action: ReturnAction) => {
    if (inFlight.current) return;
    setSnapshot(null);
    setFieldErrors({});
    setFormError(null);
    pendingFocus.current = `return-${action}`;
  };

  const heading = decision ? '반품 결정' : approval ? '반품 승인 내용' : '반품 거절 내용';
  const locked = open !== null || sending;

  return (
    <section
      ref={rootRef}
      aria-labelledby={`${id}-heading`}
      data-tour="claim-return-decision"
      className="flex flex-col gap-4 rounded-md border border-border p-4"
    >
      <h2 id={`${id}-heading`} className="font-medium">
        {heading}
      </h2>

      {decision && !decision.approvable && decision.reason && (
        <div role="status" className="flex flex-col gap-1 rounded-md bg-muted p-3 text-sm">
          <p className="font-medium">지금은 승인할 수 없습니다 — {decision.reason.name}</p>
          <p>{decision.reason.description}</p>
          {decision.rejectable && <p className="text-muted-fg">거절은 할 수 있습니다.</p>}
        </div>
      )}

      {decision && !mode.approve && decision.refundByFault.length > 0 && (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
          {decision.refundByFault.map((option) => (
            <div key={option.fault.code} className="contents">
              <dt className="text-muted-fg">정책 환불액 ({option.fault.name})</dt>
              <dd>{formatMoney(option.amount)}</dd>
            </div>
          ))}
        </dl>
      )}

      {mode.needsManager && <p className="text-sm text-muted-fg">{RETURN_MANAGER_ONLY}</p>}

      {decision && mode.approve && (
        <div className="flex flex-col gap-4">
          <fieldset disabled={locked} className="flex flex-col gap-4">
            <legend className="sr-only">승인 내용</legend>
            <fieldset
              className="flex flex-col gap-2"
              aria-describedby={errorOf('fault') ? `${id}-fault-error` : undefined}
            >
              <legend className="mb-1 text-sm font-medium">귀책</legend>
              {options.map((option, index) => (
                <label key={option.code} className="flex items-start gap-2 text-sm">
                  <input
                    type="radio"
                    name={`${id}-fault`}
                    value={option.code}
                    data-focus={index === 0 ? 'return-fault' : undefined}
                    checked={chosenFault === option.code}
                    onChange={() => {
                      setFault(option.code);
                      setFieldErrors({});
                    }}
                    className="mt-1"
                  />
                  <span className="flex flex-col">
                    <span>
                      {option.fault.name}
                      {option.code === decision.defaultFault.code && ' (기본)'}
                      {' · '}정책 환불액 {formatMoney(option.amount)}
                    </span>
                    {option.amount.amount <= 0 ? (
                      <span className="text-danger">이 귀책으로는 승인할 수 없습니다.</span>
                    ) : (
                      option.fault.description && (
                        <span className="text-muted-fg">{option.fault.description}</span>
                      )
                    )}
                  </span>
                </label>
              ))}
              <FieldError id={`${id}-fault-error`} messages={messagesOf('fault')} />
            </fieldset>

            <fieldset
              className="flex flex-col gap-2"
              aria-describedby={
                errorOf('restock') ? `${id}-restock-hint ${id}-restock-error` : `${id}-restock-hint`
              }
            >
              <legend className="mb-1 text-sm font-medium">재고 (필수)</legend>
              <p id={`${id}-restock-hint`} className="text-xs text-muted-fg">
                검수 결과에 맞게 골라 주세요. 불량 · 훼손으로 다시 팔 수 없으면 되돌리지 않습니다.
              </p>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  name={`${id}-restock`}
                  data-focus="return-restock"
                  checked={restock === true}
                  onChange={() => {
                    setRestock(true);
                    setFieldErrors({});
                  }}
                />
                재고로 되돌림
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  name={`${id}-restock`}
                  checked={restock === false}
                  onChange={() => {
                    setRestock(false);
                    setFieldErrors({});
                  }}
                />
                되돌리지 않음
              </label>
              <FieldError id={`${id}-restock-error`} messages={messagesOf('restock')} />
            </fieldset>

            <div className="flex flex-col gap-1">
              <label htmlFor={`${id}-deduction`} className="text-sm font-medium">
                차감액
                {plan.policy && ` (${deductionUnitLabel(plan.policy.currency)})`}
              </label>
              <input
                id={`${id}-deduction`}
                data-focus="return-deduction"
                inputMode={plan.policy?.currency === 'USD' ? 'decimal' : 'numeric'}
                autoComplete="off"
                value={deductionText}
                aria-invalid={errorOf('deduction') ? true : undefined}
                aria-describedby={
                  errorOf('deduction')
                    ? `${id}-deduction-hint ${id}-deduction-error`
                    : `${id}-deduction-hint`
                }
                onChange={(event) => {
                  setDeductionText(event.currentTarget.value);
                  setFieldErrors({});
                }}
                className="h-10 w-full min-w-0 rounded-md border border-border bg-bg px-3 sm:w-56"
              />
              <p id={`${id}-deduction-hint`} className="text-xs text-muted-fg">
                반품 배송비 · 훼손처럼 정책 환불액에서 뺄 금액입니다. 없으면 0.
              </p>
              <FieldError id={`${id}-deduction-error`} messages={messagesOf('deduction')} />
            </div>
          </fieldset>

          <RefundBreakdown
            faultName={faultNameOf(chosenFault)}
            policy={plan.policy}
            deduction={plan.deduction}
            refund={plan.refund}
          />

          {blocker && (blocker.notPayable || (attempted && blocker.field === null)) && (
            <p
              role="alert"
              tabIndex={-1}
              data-focus="return-blocker"
              className="text-sm text-danger"
            >
              {blocker.message}
            </p>
          )}
        </div>
      )}

      {(mode.approve || mode.reject) && !open && (
        <div className="flex flex-col gap-3 sm:flex-row">
          {mode.approve && (
            <Button
              data-focus="return-approve"
              aria-disabled={plan.blocker ? true : undefined}
              onClick={startApprove}
            >
              {RETURN_ACTION_LABEL.approve}
            </Button>
          )}
          {mode.reject && (
            <Button variant="secondary" data-focus="return-reject" onClick={startReject}>
              {RETURN_ACTION_LABEL.reject}
            </Button>
          )}
        </div>
      )}

      {open === 'approve' && pendingApproval && (
        <div
          role="group"
          aria-labelledby={`${id}-approve-title`}
          className="flex flex-col gap-3 rounded-md bg-muted p-3"
        >
          <p id={`${id}-approve-title`} className="text-sm font-medium">
            환불 예정액은 {formatMoney(pendingApproval.expectedRefundAmount)}입니다. 이 금액으로
            반품을 승인합니다.
          </p>
          <ul className="flex list-disc flex-col gap-1 pl-5 text-sm">
            <li>
              귀책 {faultNameOf(pendingApproval.fault) ?? pendingApproval.fault} · 재고{' '}
              {pendingApproval.restock ? '되돌림' : '되돌리지 않음'} · 차감액{' '}
              {formatMoney(pendingApproval.deductionAmount)}
            </li>
            <li>승인하면 바로 결제사에 환불을 요청합니다. 승인한 뒤에는 되돌릴 수 없습니다.</li>
          </ul>
          <ConfirmButtons
            label={RETURN_ACTION_LABEL.approve}
            sending={sending}
            focusKey="return-confirm-approve"
            formError={formError}
            onConfirm={() => send('approve')}
            onCancel={() => cancelConfirm('approve')}
          />
        </div>
      )}

      {open === 'reject' && (
        <div
          role="group"
          aria-labelledby={`${id}-reject-title`}
          className="flex flex-col gap-3 rounded-md bg-muted p-3"
        >
          <p id={`${id}-reject-title`} className="text-sm font-medium">
            환불 없이 이 반품을 거절합니다
          </p>
          <ul className="flex list-disc flex-col gap-1 pl-5 text-sm">
            <li>
              같은 주문에 열린 반품이 남지 않으면 주문은 배송 완료로 돌아갑니다. 거절한 뒤에는
              되돌릴 수 없습니다.
            </li>
            <li>거절 사유는 클레임 상세와 주문 이력에 남습니다.</li>
          </ul>
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-reason`} className="text-sm font-medium">
              거절 사유 (필수)
            </label>
            <textarea
              id={`${id}-reason`}
              data-focus="return-reason"
              rows={3}
              value={rejectReason}
              maxLength={DECISION_REASON_MAX}
              readOnly={sending}
              required
              aria-required="true"
              aria-invalid={errorOf('reason') ? true : undefined}
              aria-describedby={
                errorOf('reason')
                  ? `${id}-reason-hint ${id}-reason-count ${id}-reason-error`
                  : `${id}-reason-hint ${id}-reason-count`
              }
              onChange={(event) => {
                // 줄바꿈은 서버가 받지 않는 제어 문자다 — 띄어쓰기로 바꿔 둔다
                const value = event.currentTarget.value.replace(/\r\n|[\r\n]/g, ' ');
                setRejectReason(value);
              }}
              className="min-w-0 rounded-md border border-border bg-bg px-3 py-2"
            />
            <div className="flex flex-col gap-1 sm:flex-row sm:justify-between">
              <p id={`${id}-reason-hint`} className="text-xs text-muted-fg">
                예: 회수 상품이 착용 흔적이 있어 반품 기준에 맞지 않음. 클레임 상세를 볼 수 있는
                운영자에게 보이니 고객 개인정보 · 계좌번호는 적지 마세요.
              </p>
              <p id={`${id}-reason-count`} className="shrink-0 text-xs text-muted-fg">
                {rejectReason.length}/{DECISION_REASON_MAX}자
              </p>
            </div>
            <FieldError
              id={`${id}-reason-error`}
              messages={errorOf('reason') ? [errorOf('reason') ?? ''] : []}
            />
          </div>
          <ConfirmButtons
            label={RETURN_ACTION_LABEL.reject}
            sending={sending}
            focusKey="return-confirm-reject"
            formError={formError}
            onConfirm={() => send('reject')}
            onCancel={() => cancelConfirm('reject')}
          />
        </div>
      )}

      {approval && (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
          <dt className="text-muted-fg">귀책</dt>
          <dd>
            <p>{approval.fault.name}</p>
            {approval.fault.description && (
              <p className="text-muted-fg">{approval.fault.description}</p>
            )}
          </dd>
          <dt className="text-muted-fg">재고</dt>
          <dd>{approval.restock ? '재고로 되돌림' : '되돌리지 않음'}</dd>
          <dt className="text-muted-fg">차감액</dt>
          <dd>{formatMoney(approval.deduction)}</dd>
          <dt className="text-muted-fg">환불액</dt>
          <dd>{refundLabel(claim.expectedRefund)}</dd>
          <dt className="text-muted-fg">승인 시각</dt>
          <dd>{formatClaimTime(approval.approvedAt)}</dd>
        </dl>
      )}

      {rejection && (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
          <dt className="text-muted-fg">거절 사유</dt>
          <dd className="break-words">{rejection.reason}</dd>
          <dt className="text-muted-fg">거절 시각</dt>
          <dd>{formatClaimTime(rejection.decidedAt)}</dd>
        </dl>
      )}

      {notice && (
        <p
          role={notice.tone === 'error' ? 'alert' : 'status'}
          tabIndex={-1}
          data-focus="return-notice"
          className={notice.tone === 'error' ? 'text-sm text-danger' : 'text-sm'}
        >
          {notice.message}
        </p>
      )}
      {denied && <AccessNotice denied={denied} />}
    </section>
  );
}

/** 환불 예정액 내역 — 이 칸의 중심이다. 금액은 서버 정책 환불액 · 입력한 차감액 · 둘의 차(서버가 다시 대조하는 확인 값)다 */
function RefundBreakdown({
  faultName,
  policy,
  deduction,
  refund,
}: {
  faultName: string | null;
  policy: Money | null;
  deduction: Money | null;
  refund: Money | null;
}) {
  return (
    <dl className="flex flex-col gap-2 rounded-md bg-muted p-4 text-sm">
      <div className="flex items-baseline justify-between gap-4">
        <dt className="text-muted-fg">정책 환불액{faultName && ` (${faultName})`}</dt>
        <dd>{policy ? formatMoney(policy) : '—'}</dd>
      </div>
      <div className="flex items-baseline justify-between gap-4">
        <dt className="text-muted-fg">차감액</dt>
        <dd>{deduction ? `− ${formatMoney(deduction)}` : '—'}</dd>
      </div>
      <div className="flex items-baseline justify-between gap-4 border-t border-border pt-2">
        <dt className="font-medium">환불 예정액</dt>
        <dd
          aria-live="polite"
          className={
            refund && refund.amount <= 0
              ? 'text-lg font-semibold text-danger'
              : 'text-lg font-semibold'
          }
        >
          {refund ? formatMoney(refund) : '—'}
        </dd>
      </div>
    </dl>
  );
}

function ConfirmButtons({
  label,
  sending,
  focusKey,
  formError,
  onConfirm,
  onCancel,
}: {
  label: string;
  sending: boolean;
  focusKey: string;
  formError: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <>
      {formError && (
        <p
          role="alert"
          tabIndex={-1}
          data-focus="return-form-error"
          className="text-sm text-danger"
        >
          {formError}
        </p>
      )}
      <div className="flex flex-col gap-3 sm:flex-row">
        {/* 보내는 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 send 가 막는다 */}
        <Button data-focus={focusKey} aria-disabled={sending || undefined} onClick={onConfirm}>
          {sending ? '보내는 중…' : `${label} 확인`}
        </Button>
        <Button variant="secondary" aria-disabled={sending || undefined} onClick={onCancel}>
          취소
        </Button>
      </div>
    </>
  );
}
