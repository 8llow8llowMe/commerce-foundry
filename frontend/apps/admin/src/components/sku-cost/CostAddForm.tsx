'use client';

import { apiFetch, type Currency } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useId, useState } from 'react';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import { costFailure } from '@/lib/sku-cost/errors';
import {
  costForm,
  costRequest,
  CURRENCIES,
  lockedCurrency,
  withCostType,
  type CostForm,
} from '@/lib/sku-cost/form';
import {
  COST_TYPE_LABELS,
  COST_TYPES,
  REASON_MAX,
  skuCostsPath,
  type CostTypeCode,
  type SkuCostItem,
  type SkuCurrentCosts,
} from '@/lib/sku-cost/types';

const selectClass = 'h-10 rounded-md border border-border bg-bg px-3 disabled:text-muted-fg';

/**
 * 원가 한 행 추가(#395) — `POST /api/v1/admin/skus/{skuId}/costs`. 고치지 않고 새 적용 시작으로 행을 더한다(append-only).
 * 원가가 이미 있는 유형은 통화가 고정이고 "지금부터" 만 된다. 첫 원가만 지난 시각부터 적용할 수 있다.
 * "지금부터" 는 effectiveFrom 을 비워 보낸다 — 화면 시계로 채우면 서버 시각보다 앞서 `SKU_COST_006` 이 날 수 있다.
 */
export function CostAddForm({
  skuId,
  current,
  onAdded,
  onStale,
  onDenied,
}: {
  skuId: string;
  current: SkuCurrentCosts;
  /** 추가 성공 — 부모가 원가 · 이력을 다시 읽고 폼을 새로 시작한다 */
  onAdded: (item: SkuCostItem) => void;
  /** 그사이 타임라인이 바뀜 · 결과 모름 — 부모가 원가 · 이력을 다시 읽는다(폼 입력은 둔다) */
  onStale: () => void;
  onDenied: (denied: AccessDenied) => void;
}) {
  const id = useId();
  const [form, setForm] = useState<CostForm>(() => costForm(current));
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const errorsOf = (key: string) => errors[key] ?? [];
  const set = (patch: Partial<CostForm>) => setForm((value) => ({ ...value, ...patch }));

  const locked = lockedCurrency(current, form.costType);
  const currency: Currency = locked ?? form.currency;

  const submit = async () => {
    if (saving) return;
    const built = costRequest(form, current);
    if (!built.ok) {
      setErrors(built.errors);
      return;
    }
    setSaving(true);
    setErrors({});
    try {
      onAdded(
        await apiFetch<SkuCostItem>(skuCostsPath(skuId), { method: 'POST', json: built.request }),
      );
    } catch (cause) {
      const failure = costFailure(cause);
      if (failure.kind === 'access') onDenied(failure.denied);
      else {
        setErrors(failure.errors);
        if (failure.reload) onStale();
      }
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
      className="flex flex-col gap-4"
      aria-labelledby={`${id}-title`}
    >
      <h3 id={`${id}-title`} className="font-semibold">
        원가 추가
      </h3>
      <ul className="list-disc pl-5 text-xs text-muted-fg">
        <li>
          원가는 고치거나 지우지 않고 새 적용 시작으로 한 줄씩 더합니다. 지난 주문의 원가는 바뀌지
          않습니다.
        </li>
        <li>
          이미 원가가 있는 유형은 지금부터만 적용할 수 있습니다. 첫 원가만 지난 시각부터 적용할 수
          있습니다.
        </li>
        <li>통화는 그 유형에 처음 입력한 통화로 고정됩니다. 환율로 자동 변환하지 않습니다.</li>
      </ul>
      <fieldset disabled={saving} className="flex min-w-0 flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label htmlFor={`${id}-type`} className="text-sm font-medium">
            원가 유형
          </label>
          <select
            id={`${id}-type`}
            value={form.costType}
            aria-invalid={errorsOf('costType').length > 0 || undefined}
            aria-describedby={errorsOf('costType').length > 0 ? `${id}-type-error` : undefined}
            onChange={(event) =>
              setForm((value) =>
                withCostType(value, current, event.currentTarget.value as CostTypeCode),
              )
            }
            className={selectClass}
          >
            {COST_TYPES.map((costType) => (
              <option key={costType} value={costType}>
                {COST_TYPE_LABELS[costType]}
              </option>
            ))}
          </select>
          <FieldError id={`${id}-type-error`} messages={errorsOf('costType')} />
        </div>

        <div className="flex flex-wrap items-start gap-3">
          <TextField
            label={`금액 (${currency === 'KRW' ? '원' : '달러, 센트까지'})`}
            inputMode="decimal"
            value={form.amount}
            onChange={(event) => set({ amount: event.currentTarget.value })}
            errors={errorsOf('amount')}
            className="w-48"
          />
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-currency`} className="text-sm font-medium">
              통화
            </label>
            <select
              id={`${id}-currency`}
              value={currency}
              disabled={locked !== null}
              aria-invalid={errorsOf('currency').length > 0 || undefined}
              aria-describedby={`${id}-currency-help${errorsOf('currency').length > 0 ? ` ${id}-currency-error` : ''}`}
              onChange={(event) => set({ currency: event.currentTarget.value as Currency })}
              className={selectClass}
            >
              {CURRENCIES.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
            <p id={`${id}-currency-help`} className="text-xs text-muted-fg">
              {locked
                ? `${locked} 로 고정된 유형입니다.`
                : '첫 원가의 통화가 이 유형의 통화가 됩니다.'}
            </p>
            <FieldError id={`${id}-currency-error`} messages={errorsOf('currency')} />
          </div>
        </div>

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">적용 시작</legend>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="radio"
              name={`${id}-start`}
              checked={locked !== null || form.start === 'now'}
              onChange={() => set({ start: 'now' })}
            />
            지금부터
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="radio"
              name={`${id}-start`}
              disabled={locked !== null}
              checked={locked === null && form.start === 'at'}
              onChange={() => set({ start: 'at' })}
            />
            시각 지정 (첫 원가만 · 지난 시각 가능)
          </label>
          {locked === null && form.start === 'at' && (
            <TextField
              label="적용 시작 시각"
              type="datetime-local"
              value={form.effectiveFrom}
              onChange={(event) => set({ effectiveFrom: event.currentTarget.value })}
              errors={errorsOf('effectiveFrom')}
              className="w-64"
            />
          )}
          {(locked !== null || form.start === 'now') && (
            <FieldError messages={errorsOf('effectiveFrom')} />
          )}
        </fieldset>

        <TextField
          label={`변경 사유 (${REASON_MAX}자 이하)`}
          value={form.reason}
          onChange={(event) => set({ reason: event.currentTarget.value })}
          errors={errorsOf('reason')}
          placeholder="예: 10월 원단 단가 인상"
        />
      </fieldset>
      <FieldError messages={errorsOf('form')} />
      <Button type="submit" disabled={saving} className="self-start">
        {saving ? '추가 중…' : '원가 추가'}
      </Button>
    </form>
  );
}
