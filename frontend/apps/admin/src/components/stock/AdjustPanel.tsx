'use client';

import { apiFetch } from '@cf/api-client';
import { Button, Checkbox, FieldError, TextField } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import {
  adjustForm,
  adjustmentFailure,
  adjustmentRequest,
  MEMO_MAX,
  STOCK_CONFLICT_NOTICE,
  STOCK_UNKNOWN_NOTICE,
  type AdjustForm,
} from '@/lib/stock/adjustment';
import { ADJUSTMENT_REASONS, REASON_LABELS, STOCKS_PATH, type StockAdmin } from '@/lib/stock/types';

/**
 * SKU 하나의 재고 조정 폼(#188) — `PATCH /api/v1/admin/stocks/{skuId}`.
 * 수량은 결과 수량(절대값)을 넣고, 화면에서 본 수량을 기대 수량으로 함께 보낸다. 409 PRODUCT_020 이면 최신 수량을 다시 읽는다.
 * 부모가 `key` 로 SKU · 갱신 시각을 넘겨, 재고가 바뀌면 폼을 새로 시작한다.
 */
export function AdjustPanel({
  label,
  stock,
  onAdjusted,
  onStale,
  onDenied,
  onSubmit,
}: {
  label: string;
  stock: StockAdmin;
  /** 조정 성공 — 서버가 돌려준 최신 재고 */
  onAdjusted: (stock: StockAdmin) => void;
  /** 충돌 · 결과 모름 — 부모가 최신 재고를 다시 읽고 안내를 보인다 */
  onStale: (notice: string) => void;
  onDenied: (denied: AccessDenied) => void;
  /** 보내기 직전 — 부모가 이전 결과 안내를 지운다(같은 문구가 다시 읽히게) */
  onSubmit: () => void;
}) {
  const id = useId();
  const titleRef = useRef<HTMLHeadingElement>(null);
  // 열리거나(표의 조정 버튼) 다시 시작되면(조정 · 충돌 뒤 key 가 바뀜) 제목으로 포커스를 옮긴다 — 누른 버튼이 사라지거나 멀리 있어서다
  useEffect(() => {
    titleRef.current?.focus();
  }, []);
  const [form, setForm] = useState<AdjustForm>(() => adjustForm(stock));
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const errorsOf = (key: string) => errors[key] ?? [];
  const set = (patch: Partial<AdjustForm>) => setForm((current) => ({ ...current, ...patch }));

  const submit = async () => {
    if (saving) return;
    const built = adjustmentRequest(stock, form);
    if (!built.ok) {
      setErrors(built.errors);
      return;
    }
    setSaving(true);
    setErrors({});
    onSubmit();
    try {
      onAdjusted(
        await apiFetch<StockAdmin>(`${STOCKS_PATH}/${encodeURIComponent(stock.skuId)}`, {
          method: 'PATCH',
          json: built.request,
        }),
      );
    } catch (cause) {
      const failure = adjustmentFailure(cause);
      if (failure.kind === 'access') onDenied(failure.denied);
      else if (failure.kind === 'conflict') onStale(STOCK_CONFLICT_NOTICE);
      else if (failure.kind === 'unknownOutcome') onStale(STOCK_UNKNOWN_NOTICE);
      else setErrors(failure.errors);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-5"
      aria-labelledby={`${id}-title`}
    >
      <h2 id={`${id}-title`} ref={titleRef} tabIndex={-1} className="font-semibold outline-none">
        재고 조정 — {label}
      </h2>
      <fieldset disabled={saving} className="flex min-w-0 flex-col gap-4">
        <Checkbox
          label="재고 수량 관리 (끄면 무제한 판매)"
          checked={form.stockManaged}
          onChange={(event) => set({ stockManaged: event.currentTarget.checked })}
          errors={errorsOf('stockManaged')}
        />
        {form.stockManaged && (
          <div className="flex flex-col gap-1">
            <TextField
              label="조정 후 판매 가능 수량"
              inputMode="numeric"
              value={form.quantity}
              onChange={(event) => set({ quantity: event.currentTarget.value })}
              errors={errorsOf('quantity')}
            />
            <p className="text-xs text-muted-fg">
              {stock.stockManaged
                ? `지금 판매 가능 ${stock.availableQuantity}개 · 결제 대기 선점 ${stock.reservedQuantity}개. `
                : `지금은 무제한 판매입니다 · 결제 대기 선점 ${stock.reservedQuantity}개. `}
              실사 수량을 넣을 때는 선점분을 뺀 값을 넣어야 초과 판매되지 않습니다.
            </p>
          </div>
        )}
        <div className="flex flex-col gap-1">
          <label htmlFor={`${id}-reason`} className="text-sm font-medium">
            사유
          </label>
          <select
            id={`${id}-reason`}
            value={form.reason}
            aria-invalid={errorsOf('reason').length > 0 || undefined}
            aria-describedby={errorsOf('reason').length > 0 ? `${id}-reason-error` : undefined}
            onChange={(event) => set({ reason: event.currentTarget.value as AdjustForm['reason'] })}
            className="h-10 rounded-md border border-border bg-bg px-3"
          >
            <option value="">사유를 고르세요</option>
            {ADJUSTMENT_REASONS.map((reason) => (
              <option key={reason} value={reason}>
                {REASON_LABELS[reason]}
              </option>
            ))}
          </select>
          <FieldError id={`${id}-reason-error`} messages={errorsOf('reason')} />
        </div>
        <TextField
          label="메모 (선택)"
          maxLength={MEMO_MAX}
          value={form.memo}
          onChange={(event) => set({ memo: event.currentTarget.value })}
          errors={errorsOf('memo')}
        />
      </fieldset>
      <FieldError messages={errorsOf('form')} />
      <Button type="submit" disabled={saving} className="self-start">
        {saving ? '조정 중…' : '조정'}
      </Button>
    </form>
  );
}
