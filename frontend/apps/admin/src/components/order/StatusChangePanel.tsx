'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import type { CodeName } from '@/lib/product/detail';
import { normalizeReason, REASON_TOO_LONG, runStatusChange } from '@/lib/order/status-change';
import { REASON_MAX, type OrderDetail } from '@/lib/order/types';

/** 사유가 필요한 목표 — 서버가 400 `ORDER_027` 로 막는다. 화면은 안내만 하고 막지 않는다 */
const DELIVERED = 'DELIVERED';

/**
 * 주문 하나의 상태 변경(`PATCH /api/v1/admin/orders/{orderId}/status`). 버튼은 서버가 준 `nextStatuses` 만 그린다.
 * 누르면 확인 단계(사유 입력)를 거치고, 확인하면 **보내기 직전에 상세를 다시 읽어** 화면에서 본 상태 그대로인지 본다(이슈 #30).
 * 바뀌었으면 보내지 않고 최신 상세로 바꾼다. 그 뒤의 경합(고객 취소 · 다른 운영자)은 서버 `@Version` 비교가 409 `ORDER_025` 로 막는다.
 * 부모가 `key` 로 상태를 넘겨, 상세가 바뀌면 이 패널을 새로 시작한다.
 */
export function StatusChangePanel({
  order,
  onChanged,
  onStale,
  onNotFound,
  onDenied,
  onSubmit,
}: {
  order: OrderDetail;
  /** 바뀐 상세(서버 응답) */
  onChanged: (order: OrderDetail, target: CodeName) => void;
  /** 거절 · 경합 · 결과 모름 — 부모가 최신 상세로 바꾸고(`fresh` 가 있으면 그것) 안내를 보인다 */
  onStale: (notice: string, fresh: OrderDetail | null) => void;
  onNotFound: () => void;
  onDenied: (denied: AccessDenied) => void;
  /** 보내기 직전 — 부모가 이전 안내를 지운다 */
  onSubmit: () => void;
}) {
  const id = useId();
  const [target, setTarget] = useState<CodeName | null>(null);
  const [reasonText, setReasonText] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const confirmRef = useRef<HTMLHeadingElement>(null);
  // 같은 렌더 안의 두 번째 클릭은 `saving` 상태가 아직 false 다 — 보내는 중은 ref 로 막는다
  const inFlight = useRef(false);

  // 확인 단계를 열면 그 제목으로 포커스를 옮긴다 — 누른 버튼과 확인 버튼이 떨어져 있어서다
  useEffect(() => {
    if (target) confirmRef.current?.focus();
  }, [target]);

  if (order.nextStatuses.length === 0) {
    return (
      <p data-tour="order-status-actions" className="text-sm text-muted-fg">
        지금 이 화면에서 바꿀 수 있는 상태가 없습니다. 결제 · 출고(송장) · 취소 · 반품 같은 다른
        흐름이 상태를 바꿉니다.
      </p>
    );
  }

  const confirm = async () => {
    if (inFlight.current || !target) return;
    const reason = normalizeReason(reasonText);
    if (!reason.ok) {
      setErrors({ reason: [REASON_TOO_LONG] });
      return;
    }
    inFlight.current = true;
    setSaving(true);
    setErrors({});
    onSubmit();
    const outcome = await runStatusChange(apiFetch, order, target.code, reason.reason);
    inFlight.current = false;
    setSaving(false);
    if (outcome.kind === 'changed') onChanged(outcome.order, target);
    else if (outcome.kind === 'stale') onStale(outcome.notice, outcome.fresh);
    else if (outcome.kind === 'access') onDenied(outcome.denied);
    else if (outcome.kind === 'notFound') onNotFound();
    else setErrors(outcome.errors);
  };

  return (
    <div className="flex flex-col gap-4">
      <div
        data-tour="order-status-actions"
        className="flex flex-wrap gap-3"
        role="group"
        aria-label="바꿀 상태"
      >
        {order.nextStatuses.map((status) => (
          <Button
            key={status.code}
            variant={target?.code === status.code ? 'primary' : 'secondary'}
            aria-pressed={target?.code === status.code}
            title={status.description}
            disabled={saving}
            onClick={() => {
              setTarget(status);
              setErrors({});
            }}
          >
            {status.name}(으)로 변경
          </Button>
        ))}
      </div>

      {target && (
        <div className="flex flex-col gap-3 rounded-md bg-muted px-4 py-4">
          <h3 ref={confirmRef} tabIndex={-1} className="font-medium outline-none">
            {order.status.name} → {target.name}
          </h3>
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-reason`} className="text-sm font-medium">
              {target.code === DELIVERED ? '사유 (배송을 확인한 근거, 필수)' : '사유 (선택)'}
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
              주문 이력에 남습니다. {REASON_MAX}자 이하.
            </p>
            <FieldError id={`${id}-reason-error`} messages={errors.reason ?? []} />
          </div>
          <FieldError messages={errors.form ?? []} />
          <div className="flex gap-3">
            <Button onClick={() => void confirm()} disabled={saving}>
              {saving ? '바꾸는 중…' : '확인'}
            </Button>
            <Button variant="secondary" onClick={() => setTarget(null)} disabled={saving}>
              취소
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
