'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useId, useRef, useState } from 'react';
import { SESSION_EXPIRED_ON_SAVE, type AccessDenied } from '@/lib/api-errors';
import {
  CARRIER_LABELS,
  CARRIERS,
  isCarrier,
  isRetryable,
  runShipmentRegistration,
  TRACKING_NUMBER_MAX,
  validateDrafts,
  type CarrierCode,
  type DraftErrors,
  type ShipmentRow,
} from '@/lib/order/shipment';

/**
 * 주문 하나의 송장 등록 · 출고(`POST /api/v1/admin/deliveries`, 1건짜리 목록). 상품 준비중 주문에만 그린다 — 다른 상태는 서버가
 * `DELIVERY_015` 로 거절하고, 출고 뒤 송장 조회 · 배송 상태 변경은 BE PR-B2 가 열리면 붙인다.
 * 결과가 어떻든 부모가 상세를 다시 읽어 배송 중 반영을 확인한다(`onResult`). 다시 보낼 만한 실패면 입력을 그대로 두어 같은 송장으로 다시 보낸다.
 * 보내는 중에는 버튼을 막는다(자동 재시도 없음).
 */
export function ShipmentPanel({
  orderId,
  onResult,
  onDenied,
  onSubmit,
}: {
  orderId: string;
  /** 한 번 보낸 결과 — 부모가 상세를 다시 읽고 안내한다 */
  onResult: (row: ShipmentRow) => void;
  onDenied: (denied: AccessDenied) => void;
  /** 보내기 직전 — 부모가 이전 안내를 지운다 */
  onSubmit: () => void;
}) {
  const id = useId();
  const [carrier, setCarrier] = useState<CarrierCode | ''>('');
  const [trackingText, setTrackingText] = useState('');
  const [errors, setErrors] = useState<DraftErrors[string]>({});
  const [hint, setHint] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // 같은 렌더 안의 두 번째 클릭은 `saving` 상태가 아직 false 다 — 보내는 중은 ref 로 막는다
  const inFlight = useRef(false);

  const submit = async () => {
    if (inFlight.current) return;
    const checked = validateDrafts([{ orderId, label: orderId, carrier, trackingText }]);
    if (!checked.ok) {
      setErrors(checked.errors[orderId] ?? {});
      return;
    }
    inFlight.current = true;
    setSaving(true);
    setErrors({});
    setHint(null);
    setFormError(null);
    onSubmit();
    const run = await runShipmentRegistration(apiFetch, checked.items);
    inFlight.current = false;
    setSaving(false);
    if (run.denied) {
      onDenied(run.denied);
      return;
    }
    // 저장 중 401 — 다시 읽으면 로그인 화면으로 넘어가 입력이 사라진다. 입력을 두고 여기서 안내만 한다
    if (run.sessionExpired) {
      setFormError(SESSION_EXPIRED_ON_SAVE);
      return;
    }
    const row = run.rows[0];
    if (!row) return;
    if (isRetryable(row))
      setHint('입력한 송장을 그대로 두었습니다. 같은 송장으로 다시 등록해 주세요.');
    onResult(row);
  };

  return (
    <section
      aria-labelledby={`${id}-title`}
      data-tour="order-shipment"
      className="flex flex-col gap-4 rounded-md border border-border p-4"
    >
      <div className="flex flex-col gap-1">
        <h2 id={`${id}-title`} className="font-medium">
          송장 등록 (출고)
        </h2>
        <p className="text-sm text-muted-fg">
          송장을 등록하면 주문이 배송 중으로 바뀝니다. 출고된 송장은 바꿀 수 없으니 번호를 확인해
          주세요.
        </p>
      </div>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
        <div className="flex min-w-0 flex-col gap-1 sm:w-44">
          <label htmlFor={`${id}-carrier`} className="text-sm font-medium">
            택배사
          </label>
          <select
            id={`${id}-carrier`}
            value={carrier}
            disabled={saving}
            aria-invalid={errors.carrier ? true : undefined}
            aria-describedby={errors.carrier ? `${id}-carrier-error` : undefined}
            onChange={(event) => {
              const value = event.currentTarget.value;
              setCarrier(isCarrier(value) ? value : '');
            }}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">선택</option>
            {CARRIERS.map((code) => (
              <option key={code} value={code}>
                {CARRIER_LABELS[code]}
              </option>
            ))}
          </select>
          <FieldError
            id={`${id}-carrier-error`}
            messages={errors.carrier ? [errors.carrier] : []}
          />
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <label htmlFor={`${id}-tracking`} className="text-sm font-medium">
            송장 번호
          </label>
          <input
            id={`${id}-tracking`}
            value={trackingText}
            maxLength={TRACKING_NUMBER_MAX + 10}
            disabled={saving}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={errors.trackingNumber ? true : undefined}
            aria-describedby={`${id}-tracking-hint${errors.trackingNumber ? ` ${id}-tracking-error` : ''}`}
            onChange={(event) => setTrackingText(event.currentTarget.value)}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          />
          <p id={`${id}-tracking-hint`} className="text-xs text-muted-fg">
            영문 · 숫자 · 하이픈, {TRACKING_NUMBER_MAX}자 이하. 입력한 그대로 저장합니다.
          </p>
          <FieldError
            id={`${id}-tracking-error`}
            messages={errors.trackingNumber ? [errors.trackingNumber] : []}
          />
        </div>
        <Button onClick={() => void submit()} disabled={saving} className="sm:mt-6">
          {saving ? '등록하는 중…' : '송장 등록'}
        </Button>
      </div>
      <FieldError messages={formError ? [formError] : []} />
      {hint && <p className="text-sm text-muted-fg">{hint}</p>}
    </section>
  );
}
