'use client';

import type { Currency } from '@cf/api-client';
import { Button, FieldError, TextField, type TextFieldProps } from '@cf/ui';
import { useId, type ReactNode } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED } from '@/lib/api-errors';
import type { FormErrors } from '@/lib/shipping/policy-form';
import type { PolicyEditor } from '@/lib/shipping/use-policy-editor';

const CURRENCY_UNIT: Record<Currency, string> = { KRW: '원', USD: 'USD' };

/** 칸 키의 오류 문구(없으면 빈 목록) */
export const errorsOf = (errors: FormErrors, key: string): string[] => errors[key] ?? [];

/** 금액 입력칸 — 정책 통화의 주 단위로 받는다(KRW 원, USD 달러·센트 소수 둘째 자리까지) */
export function MoneyField({
  currency,
  label,
  ...rest
}: Omit<TextFieldProps, 'inputMode' | 'type'> & { currency: Currency }) {
  return (
    <TextField
      {...rest}
      label={`${label} (${CURRENCY_UNIT[currency]})`}
      inputMode={currency === 'KRW' ? 'numeric' : 'decimal'}
      autoComplete="off"
    />
  );
}

/**
 * 정책 통화. 처음 만들 때만 고른다 — 저장된 정책의 통화는 바꿀 수 없다(DELIVERY_012).
 * 정책 안의 금액은 모두 이 통화로 보낸다(다르면 DELIVERY_003).
 */
export function CurrencyField({
  currency,
  editable,
  errors,
  onChange,
}: {
  currency: Currency;
  editable: boolean;
  errors: string[];
  onChange: (currency: Currency) => void;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  const invalid = errors.length > 0;

  return (
    <div className="flex flex-col gap-1">
      {editable ? (
        <label htmlFor={id} className="text-sm font-medium">
          통화
        </label>
      ) : (
        <p className="text-sm font-medium">통화</p>
      )}
      {editable ? (
        <select
          id={id}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? errorId : undefined}
          value={currency}
          onChange={(event) => onChange(event.currentTarget.value as Currency)}
          className="h-10 rounded-md border border-border bg-bg px-3"
        >
          <option value="KRW">KRW (원)</option>
          <option value="USD">USD</option>
        </select>
      ) : (
        <p
          aria-describedby={invalid ? errorId : undefined}
          className="flex h-10 items-center text-sm"
        >
          {currency}{' '}
          <span className="ml-2 text-muted-fg">저장된 정책의 통화는 바꿀 수 없습니다.</span>
        </p>
      )}
      <FieldError id={errorId} messages={errors} />
    </div>
  );
}

export function Section({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: ReactNode;
}) {
  return (
    <section className="flex flex-col gap-4 rounded-md bg-muted/50 p-5">
      <div>
        <h2 className="font-semibold">{title}</h2>
        {description && <p className="mt-1 text-sm text-muted-fg">{description}</p>}
      </div>
      {children}
    </section>
  );
}

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/**
 * 정책 편집 화면의 틀 — 읽는 중 · 로그인 필요 · 읽기 실패를 그리고, 준비되면 폼(children)을 그린다.
 * 폼 아래에 폼 전체 오류 · 저장 안내 · 저장 버튼을 둔다. `after` 는 폼이 준비됐을 때 그 아래에 그린다(변경 이력, #319).
 */
export function PolicyEditorFrame<
  Form extends { version: number | null; updatedAt: string | null },
>({
  title,
  editor,
  children,
  after,
}: {
  title: string;
  editor: PolicyEditor<Form>;
  children: (form: Form) => ReactNode;
  after?: ReactNode;
}) {
  const { status, form } = editor;

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">{title}</h1>
        {status.kind === 'ready' && form && (
          <p className="mt-1 text-sm text-muted-fg">
            {form.version === null
              ? '아직 설정한 정책이 없습니다. 저장하면 새로 만듭니다.'
              : form.updatedAt && `마지막 저장 ${dateTime.format(new Date(form.updatedAt))}`}
          </p>
        )}
      </div>

      {status.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {status.kind === 'denied' && <AccessNotice denied={status.denied} />}
      {status.kind === 'loadFailed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => void editor.reload()}>
            다시 불러오기
          </Button>
        </div>
      )}

      {status.kind === 'ready' && form && (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void editor.save();
          }}
          className="flex flex-col gap-6"
        >
          {/* 저장 중에는 칸을 잠근다 — 성공 응답이 폼을 서버 값으로 바꾸므로 그사이 친 값이 말없이 사라지지 않게 */}
          <fieldset disabled={editor.saving} className="flex min-w-0 flex-col gap-6">
            {children(form)}
          </fieldset>
          <div className="flex flex-col gap-3">
            <FieldError messages={errorsOf(editor.errors, 'form')} />
            {editor.notice && (
              <p role="status" className="text-sm">
                {editor.notice}
              </p>
            )}
            <Button type="submit" disabled={editor.saving} className="self-start">
              {editor.saving ? '저장 중…' : '저장'}
            </Button>
          </div>
        </form>
      )}
      {status.kind === 'ready' && form && after}
    </div>
  );
}
