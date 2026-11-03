'use client';

import { cx, FieldError, TextField } from '@cf/ui';
import { useId } from 'react';
import type { FormErrors } from '@/lib/api-errors';
import type { ContentForm } from '@/lib/knowledge/form';
import {
  CATEGORY_LABELS,
  KNOWLEDGE_CATEGORIES,
  LIMITS,
  type KnowledgeCategoryCode,
} from '@/lib/knowledge/types';

const control = 'rounded-md border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary';

/**
 * 지식 내용 칸(제목 · 카테고리 · 본문 · 유효기간) — 등록 · 초안 고치기 · 새 초안이 함께 쓴다.
 * 본문은 plain text 다. 줄바꿈은 그대로 저장되고 HTML 은 해석하지 않는다.
 */
export function ContentFields({
  form,
  errors,
  onChange,
}: {
  form: ContentForm;
  errors: FormErrors;
  onChange: (next: ContentForm) => void;
}) {
  const id = useId();
  const set = <K extends keyof ContentForm>(key: K, value: ContentForm[K]) =>
    onChange({ ...form, [key]: value });
  const categoryErrors = errors.category ?? [];
  const bodyErrors = errors.body ?? [];

  return (
    <>
      <TextField
        label="제목"
        value={form.title}
        errors={errors.title}
        maxLength={LIMITS.title}
        onChange={(event) => set('title', event.currentTarget.value)}
      />
      <div className="flex flex-col gap-1 sm:w-60">
        <label htmlFor={`${id}-category`} className="text-sm font-medium">
          카테고리
        </label>
        <select
          id={`${id}-category`}
          value={form.category}
          aria-invalid={categoryErrors.length > 0 || undefined}
          aria-describedby={categoryErrors.length > 0 ? `${id}-category-error` : undefined}
          onChange={(event) =>
            set(
              'category',
              KNOWLEDGE_CATEGORIES.find((code) => code === event.currentTarget.value) ??
                ('' as KnowledgeCategoryCode | ''),
            )
          }
          className={cx(
            control,
            'h-10',
            categoryErrors.length > 0 ? 'border-danger' : 'border-border',
          )}
        >
          <option value="">고르기</option>
          {KNOWLEDGE_CATEGORIES.map((code) => (
            <option key={code} value={code}>
              {CATEGORY_LABELS[code]}
            </option>
          ))}
        </select>
        <FieldError id={`${id}-category-error`} messages={categoryErrors} />
      </div>
      <div className="flex flex-col gap-1">
        <label htmlFor={`${id}-body`} className="text-sm font-medium">
          본문
        </label>
        <textarea
          id={`${id}-body`}
          rows={12}
          value={form.body}
          aria-invalid={bodyErrors.length > 0 || undefined}
          aria-describedby={`${id}-body-hint${bodyErrors.length > 0 ? ` ${id}-body-error` : ''}`}
          onChange={(event) => set('body', event.currentTarget.value)}
          className={cx(control, 'py-2', bodyErrors.length > 0 ? 'border-danger' : 'border-border')}
        />
        <p id={`${id}-body-hint`} className="text-xs text-muted-fg">
          글자 그대로 저장됩니다(HTML 은 쓰지 않습니다). {form.body.length.toLocaleString('ko-KR')}{' '}
          / {LIMITS.body.toLocaleString('ko-KR')}자
        </p>
        <FieldError id={`${id}-body-error`} messages={bodyErrors} />
      </div>
      <div className="flex flex-col gap-4 sm:flex-row">
        <TextField
          label="유효 시작"
          type="datetime-local"
          value={form.validFrom}
          errors={errors.validFrom}
          onChange={(event) => set('validFrom', event.currentTarget.value)}
          className="sm:w-60"
        />
        <TextField
          label="유효 종료(비우면 무기한)"
          type="datetime-local"
          value={form.validTo}
          errors={errors.validTo}
          onChange={(event) => set('validTo', event.currentTarget.value)}
          className="sm:w-60"
        />
      </div>
    </>
  );
}
