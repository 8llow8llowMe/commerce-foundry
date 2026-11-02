'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useId, useRef, useState } from 'react';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import {
  BULK_UNKNOWN_NOTICE,
  normalizeReason,
  REASON_TOO_LONG,
  retryableOrderIds,
  runBulkChange,
} from '@/lib/order/status-change';
import {
  BULK_TARGET_STATUSES,
  ORDER_STATUS_LABELS,
  REASON_MAX,
  type BulkOutcome,
  type BulkStatusChangeRequest,
  type BulkTargetStatus,
  type OrderListItem,
} from '@/lib/order/types';
import { BulkResult } from './BulkResult';

/**
 * 고른 주문의 상태를 한 번에 바꾼다(`POST /api/v1/admin/orders/status-changes`).
 * 목표 상태는 어드민이 직접 바꾸는 전이의 목표만 고른다(배송 완료는 한 건씩 — 상세에서). 주문마다 바뀔 수 있는지는 서버가 정하고,
 * 결과는 행별로(바꿈 · 이미 그 상태 · 실패) 보인다. 일부 실패를 전체 성공처럼 말하지 않는다.
 * 요청 중에는 버튼을 막는다. 결과를 모르는 실패(502 · 504 · 연결 끊김)는 자동으로 다시 보내지 않고 목록을 다시 읽는다.
 */
export function BulkStatusPanel({
  selected,
  onSent,
  onDenied,
}: {
  /** 고른 주문 — 목록 순서 */
  selected: OrderListItem[];
  /** 서버가 요청을 받았을 수 있다 — 부모가 고른 것을 비우고 목록을 다시 읽는다 */
  onSent: () => void;
  onDenied: (denied: AccessDenied) => void;
}) {
  const id = useId();
  const [target, setTarget] = useState<BulkTargetStatus | ''>('');
  const [reasonText, setReasonText] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const [outcome, setOutcome] = useState<BulkOutcome | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  // 같은 렌더 안의 두 번째 클릭은 `saving` 상태가 아직 false 다 — 보내는 중은 ref 로 막는다
  const inFlight = useRef(false);

  const send = async (request: BulkStatusChangeRequest, names: Record<string, string>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setSaving(true);
    setErrors({});
    setNotice(null);
    const result = await runBulkChange(apiFetch, request);
    inFlight.current = false;
    setSaving(false);
    if (result.kind === 'done') {
      setOutcome({ request, response: result.response, names });
      onSent();
    } else if (result.kind === 'access') onDenied(result.denied);
    else if (result.kind === 'unknownOutcome') {
      setOutcome(null);
      setNotice(BULK_UNKNOWN_NOTICE);
      onSent();
    } else setErrors(result.errors);
  };

  const submit = () => {
    const nextErrors: FormErrors = {};
    if (selected.length === 0) nextErrors.form = ['바꿀 주문을 골라 주세요.'];
    if (target === '') nextErrors.target = ['바꿀 상태를 골라 주세요.'];
    const reason = normalizeReason(reasonText);
    if (!reason.ok) nextErrors.reason = [REASON_TOO_LONG];
    if (Object.keys(nextErrors).length > 0 || target === '' || !reason.ok) {
      setErrors(nextErrors);
      return;
    }
    void send(
      { orderIds: selected.map((order) => order.orderId), status: target, reason: reason.reason },
      Object.fromEntries(selected.map((order) => [order.orderId, order.orderName])),
    );
  };

  const retryFailed = () => {
    if (!outcome) return;
    const orderIds = retryableOrderIds(outcome.response);
    if (orderIds.length === 0) return;
    void send({ ...outcome.request, orderIds }, outcome.names);
  };

  return (
    <section
      aria-labelledby={`${id}-title`}
      data-tour="order-bulk"
      className="flex flex-col gap-4 rounded-md border border-border p-4"
    >
      <div className="flex flex-col gap-1">
        <h2 id={`${id}-title`} className="font-medium">
          상태 일괄 변경
        </h2>
        <p className="text-sm text-muted-fg">
          {selected.length > 0
            ? `고른 주문 ${selected.length}건`
            : '표에서 주문을 고르면 한 번에 바꿀 수 있습니다.'}{' '}
          바꿀 수 없는 주문은 건너뛰고 결과에 이유를 보입니다. 배송 완료는 주문 상세에서 한 건씩
          바꿉니다.
        </p>
      </div>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
        <div className="flex min-w-0 flex-col gap-1 sm:w-44">
          <label htmlFor={`${id}-target`} className="text-sm font-medium">
            바꿀 상태
          </label>
          <select
            id={`${id}-target`}
            value={target}
            aria-invalid={errors.target ? true : undefined}
            aria-describedby={errors.target ? `${id}-target-error` : undefined}
            onChange={(event) => {
              const value = event.currentTarget.value;
              setTarget(BULK_TARGET_STATUSES.find((status) => status === value) ?? '');
            }}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">선택</option>
            {BULK_TARGET_STATUSES.map((status) => (
              <option key={status} value={status}>
                {ORDER_STATUS_LABELS[status]}
              </option>
            ))}
          </select>
          <FieldError id={`${id}-target-error`} messages={errors.target ?? []} />
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <label htmlFor={`${id}-reason`} className="text-sm font-medium">
            사유 (선택)
          </label>
          <input
            id={`${id}-reason`}
            value={reasonText}
            maxLength={REASON_MAX}
            aria-invalid={errors.reason ? true : undefined}
            aria-describedby={`${id}-reason-hint${errors.reason ? ` ${id}-reason-error` : ''}`}
            onChange={(event) => setReasonText(event.currentTarget.value)}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          />
          <p id={`${id}-reason-hint`} className="text-xs text-muted-fg">
            고른 모든 주문의 이력에 남습니다. {REASON_MAX}자 이하.
          </p>
          <FieldError id={`${id}-reason-error`} messages={errors.reason ?? []} />
        </div>
        <Button onClick={submit} disabled={saving || selected.length === 0} className="sm:mt-6">
          {saving ? '바꾸는 중…' : '일괄 변경'}
        </Button>
      </div>
      <FieldError messages={errors.form ?? []} />
      {notice && (
        <p role="status" className="text-sm text-danger">
          {notice}
        </p>
      )}
      {outcome && (
        <BulkResult
          outcome={outcome}
          retrying={saving}
          onRetryFailed={retryFailed}
          onDismiss={() => setOutcome(null)}
        />
      )}
    </section>
  );
}
