'use client';

import { Button, Checkbox, FieldError, TextField } from '@cf/ui';
import { useId } from 'react';
import {
  emptyCustomField,
  FIELDS_MAX,
  HELP_TEXT_MAX,
  INPUT_TYPE_LABELS,
  LABEL_MAX,
  LEAD_TIME_MAX,
  NOTICE_MAX,
  TEXT_LENGTH_MAX,
  UNIT_MAX,
  type CustomFieldForm,
  type CustomInputType,
  type CustomOrderForm,
} from '@/lib/product/custom-order';
import { moveSlot } from '@/lib/product/section';

const linkButton = 'w-fit text-sm text-muted-fg underline underline-offset-4 disabled:opacity-50';

/**
 * 맞춤 제작 설정 칸 — 사용 여부 · 안내 · 소요일 · 입력 항목. 상품 등록(본문 `customOrder`)과 맞춤 제작 수정(#29 · BE #580)이 같이 쓴다.
 * `errorsOf` 는 이 칸들의 키(`notice` · `fields.0.label`)로 묻는다 — 등록 화면은 앞에 `customOrder.` 를 붙여 넘긴다.
 * 저장한 항목(`saved`)의 키는 고칠 수 없다(주문이 키로 입력값을 남긴다).
 */
export function CustomOrderFields({
  form,
  onChange,
  errorsOf,
  busy,
  enabledLabel,
}: {
  form: CustomOrderForm;
  onChange: (update: (value: CustomOrderForm) => CustomOrderForm) => void;
  errorsOf: (key: string) => string[];
  busy: boolean;
  enabledLabel: string;
}) {
  const id = useId();
  const set = (patch: Partial<CustomOrderForm>) => onChange((value) => ({ ...value, ...patch }));
  const setFields = (fields: CustomFieldForm[]) => set({ fields });
  const patchField = (fieldId: string, patch: Partial<CustomFieldForm>) =>
    onChange((value) => ({
      ...value,
      fields: value.fields.map((field) => (field.id === fieldId ? { ...field, ...patch } : field)),
    }));

  return (
    <>
      <Checkbox
        label={enabledLabel}
        checked={form.enabled}
        onChange={(event) => set({ enabled: event.currentTarget.checked })}
        errors={errorsOf('enabled')}
      />
      <div className="flex flex-col gap-1">
        <label htmlFor={`${id}-notice`} className="text-sm font-medium">
          고객 안내 문구 (선택, {NOTICE_MAX}자 이하, 줄바꿈 가능)
        </label>
        <textarea
          id={`${id}-notice`}
          rows={4}
          value={form.notice}
          onChange={(event) => set({ notice: event.currentTarget.value })}
          aria-invalid={errorsOf('notice').length > 0 || undefined}
          className="rounded-md border border-border bg-bg px-3 py-2"
        />
        <FieldError messages={errorsOf('notice')} />
      </div>
      <TextField
        label={`제작 소요일 (선택, 1~${LEAD_TIME_MAX}일)`}
        inputMode="numeric"
        value={form.leadTimeDays}
        onChange={(event) => set({ leadTimeDays: event.currentTarget.value })}
        errors={errorsOf('leadTimeDays')}
        className="w-48"
      />

      <fieldset className="flex flex-col gap-4">
        <legend className="mb-2 text-sm font-medium">
          입력 항목 {form.fields.length}개 ({FIELDS_MAX}개까지, 위에서부터 고객에게 보입니다)
        </legend>
        {form.fields.length === 0 && <p className="text-sm text-muted-fg">입력 항목이 없습니다.</p>}
        {form.fields.map((field, index) => {
          const at = (name: string) => errorsOf(`fields.${index}.${name}`);
          const number = field.inputType === 'NUMBER';
          return (
            <div
              key={field.id}
              className="flex flex-col gap-3 rounded-md border border-border p-4"
              aria-label={`입력 항목 ${index + 1}`}
              role="group"
            >
              <div className="flex flex-wrap items-start gap-3">
                <TextField
                  label={field.saved ? '키 (저장한 키는 고칠 수 없습니다)' : '키 (예: chest)'}
                  value={field.fieldKey}
                  readOnly={field.saved}
                  onChange={(event) =>
                    patchField(field.id, { fieldKey: event.currentTarget.value })
                  }
                  errors={at('fieldKey')}
                  className="w-56"
                />
                <TextField
                  label={`이름 (${LABEL_MAX}자 이하)`}
                  value={field.label}
                  onChange={(event) => patchField(field.id, { label: event.currentTarget.value })}
                  errors={at('label')}
                  className="w-56"
                />
                <div className="flex flex-col gap-1">
                  <label htmlFor={`${id}-type-${field.id}`} className="text-sm font-medium">
                    입력 방식
                  </label>
                  <select
                    id={`${id}-type-${field.id}`}
                    value={field.inputType}
                    onChange={(event) =>
                      patchField(field.id, {
                        inputType: event.currentTarget.value as CustomInputType,
                      })
                    }
                    className="h-10 w-28 rounded-md border border-border bg-bg px-3"
                  >
                    {(Object.keys(INPUT_TYPE_LABELS) as CustomInputType[]).map((code) => (
                      <option key={code} value={code}>
                        {INPUT_TYPE_LABELS[code]}
                      </option>
                    ))}
                  </select>
                  <FieldError messages={at('inputType')} />
                </div>
                <TextField
                  label={`단위 (선택, ${UNIT_MAX}자)`}
                  value={field.unit}
                  onChange={(event) => patchField(field.id, { unit: event.currentTarget.value })}
                  errors={at('unit')}
                  className="w-28"
                />
              </div>
              <div className="flex flex-wrap items-start gap-3">
                {number && (
                  <TextField
                    label="최소값 (선택)"
                    inputMode="decimal"
                    value={field.minValue}
                    onChange={(event) =>
                      patchField(field.id, { minValue: event.currentTarget.value })
                    }
                    errors={at('minValue')}
                    className="w-36"
                  />
                )}
                <TextField
                  label={number ? '최대값 (선택)' : `최대 글자 수 (선택, 1~${TEXT_LENGTH_MAX})`}
                  inputMode={number ? 'decimal' : 'numeric'}
                  value={field.maxValue}
                  onChange={(event) =>
                    patchField(field.id, { maxValue: event.currentTarget.value })
                  }
                  errors={at('maxValue')}
                  className={number ? 'w-36' : 'w-56'}
                />
                {number && (
                  <TextField
                    label="증분 (선택, 예: 0.5)"
                    inputMode="decimal"
                    value={field.stepValue}
                    onChange={(event) =>
                      patchField(field.id, { stepValue: event.currentTarget.value })
                    }
                    errors={at('stepValue')}
                    className="w-36"
                  />
                )}
              </div>
              <TextField
                label={`도움말 (선택, ${HELP_TEXT_MAX}자 이하)`}
                value={field.helpText}
                onChange={(event) => patchField(field.id, { helpText: event.currentTarget.value })}
                errors={at('helpText')}
              />
              <Checkbox
                label="필수 입력"
                checked={field.required}
                onChange={(event) =>
                  patchField(field.id, { required: event.currentTarget.checked })
                }
              />
              <div className="flex flex-wrap gap-4">
                <button
                  type="button"
                  disabled={busy || index === 0}
                  onClick={() => setFields(moveSlot(form.fields, index, -1))}
                  className={linkButton}
                >
                  위로
                </button>
                <button
                  type="button"
                  disabled={busy || index === form.fields.length - 1}
                  onClick={() => setFields(moveSlot(form.fields, index, 1))}
                  className={linkButton}
                >
                  아래로
                </button>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => setFields(form.fields.filter((other) => other.id !== field.id))}
                  className={linkButton}
                >
                  항목 지우기
                </button>
              </div>
            </div>
          );
        })}
        <Button
          variant="secondary"
          disabled={busy || form.fields.length >= FIELDS_MAX}
          onClick={() => setFields([...form.fields, emptyCustomField(crypto.randomUUID())])}
          className="w-fit"
        >
          입력 항목 더하기
        </Button>
        <FieldError messages={errorsOf('fields')} />
      </fieldset>
    </>
  );
}
