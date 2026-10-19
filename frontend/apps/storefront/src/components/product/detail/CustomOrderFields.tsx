import { useId } from 'react';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { fill } from '@/lib/i18n/format';
import type { CustomOrder } from '@/lib/catalog/types';

/** fieldKey → 입력값. 주문 생성 때 그대로 보낸다(#26) */
export type CustomOrderValues = Record<string, string>;

/**
 * 맞춤 제작 치수 · 요청 입력. 입력 항목 정의는 상품 데이터(customOrder.fields)가 정한다.
 * 브라우저 검증 속성(min · max · step · maxLength · required)만 건다 — 최종 검증은 주문 생성 때 서버가 한다(#97).
 */
export function CustomOrderFields({
  customOrder,
  values,
  onChange,
}: {
  customOrder: CustomOrder;
  values: CustomOrderValues;
  onChange: (fieldKey: string, value: string) => void;
}) {
  const baseId = useId();
  const t = useDictionary().product.customOrder;

  return (
    <fieldset className="flex flex-col gap-4 border-t border-border pt-6">
      <legend className="float-left mb-2 w-full text-xs tracking-[0.15em] uppercase">
        {t.title}
      </legend>
      {(customOrder.notice || customOrder.leadTimeDays) && (
        <div className="flex flex-col gap-1 bg-accent p-4 text-sm text-accent-fg">
          {customOrder.leadTimeDays !== null && (
            <p className="font-medium">{fill(t.leadTime, { days: customOrder.leadTimeDays })}</p>
          )}
          {customOrder.notice && <p className="whitespace-pre-line">{customOrder.notice}</p>}
        </div>
      )}

      {customOrder.fields.map((field) => {
        const id = `${baseId}-${field.fieldKey}`;
        const helpId = field.helpText ? `${id}-help` : undefined;
        const number = field.inputType === 'NUMBER';
        return (
          <div key={field.fieldKey} className="flex flex-col gap-1">
            <label htmlFor={id} className="text-sm">
              {field.label}
              {field.unit && <span className="text-muted-fg"> ({field.unit})</span>}
              {field.required ? (
                <span className="text-muted-fg"> · {t.required}</span>
              ) : (
                <span className="text-muted-fg"> · {t.optional}</span>
              )}
            </label>
            <input
              id={id}
              name={field.fieldKey}
              type={number ? 'number' : 'text'}
              inputMode={number ? 'decimal' : undefined}
              required={field.required}
              min={number ? (field.min ?? undefined) : undefined}
              max={number ? (field.max ?? undefined) : undefined}
              step={number ? (field.step ?? 'any') : undefined}
              maxLength={!number && field.max !== null ? field.max : undefined}
              aria-describedby={helpId}
              value={values[field.fieldKey] ?? ''}
              onChange={(event) => onChange(field.fieldKey, event.target.value)}
              className="h-10 rounded-md border border-border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary"
            />
            {field.helpText && (
              <p id={helpId} className="text-xs text-muted-fg">
                {field.helpText}
              </p>
            )}
          </div>
        );
      })}
    </fieldset>
  );
}
