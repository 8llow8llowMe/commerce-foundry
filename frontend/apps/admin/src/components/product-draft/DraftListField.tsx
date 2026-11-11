'use client';

import { Button, FieldError } from '@cf/ui';
import { useId, useState } from 'react';
import {
  addListItem,
  LIST_ITEM_MAX,
  LIST_ITEMS_MAX,
  removeListItem,
} from '@/lib/product-draft/form';

/**
 * 목록 사실 칸(소재 · 세탁 · 인증 · 꼭 넣을/쓰지 않을 표현, #360). 한 줄씩 입력해 "더하기"(또는 Enter)로 칩을 더하고 칩의 "빼기"로 지운다.
 * 규칙은 BE 와 같다 — 항목 1~100자 한 줄, 10개까지(`addListItem`). 입력 중인 글은 더하기 전까지 본문에 실리지 않는다.
 */
export function DraftListField({
  label,
  hint,
  items,
  onChange,
  errors = [],
  disabled = false,
}: {
  label: string;
  hint?: string;
  items: string[];
  onChange: (items: string[]) => void;
  errors?: readonly string[];
  disabled?: boolean;
}) {
  const id = useId();
  const [input, setInput] = useState('');
  const [error, setError] = useState<string | null>(null);
  const messages = error ? [error, ...errors] : errors;
  const full = items.length >= LIST_ITEMS_MAX;

  const add = () => {
    const result = addListItem(items, input);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    setError(null);
    setInput('');
    onChange(result.items);
  };

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={`${id}-input`} className="text-sm font-medium">
        {label} (선택, {LIST_ITEMS_MAX}개까지)
      </label>
      {items.length > 0 && (
        <ul className="flex flex-wrap gap-2" aria-label={`${label} 목록`}>
          {items.map((item, index) => (
            <li
              key={item}
              className="flex items-center gap-2 rounded-md border border-border bg-muted px-2 py-1 text-sm"
            >
              <span className="break-all">{item}</span>
              <button
                type="button"
                disabled={disabled}
                aria-label={`${label} "${item}" 빼기`}
                onClick={() => {
                  setError(null);
                  onChange(removeListItem(items, index));
                }}
                className="text-muted-fg underline underline-offset-4 disabled:opacity-50"
              >
                빼기
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <input
          id={`${id}-input`}
          value={input}
          maxLength={LIST_ITEM_MAX * 2}
          disabled={disabled || full}
          aria-invalid={messages.length > 0 || undefined}
          aria-describedby={[hint ? `${id}-hint` : null, messages.length > 0 ? `${id}-error` : null]
            .filter(Boolean)
            .join(' ')}
          onChange={(event) => setInput(event.currentTarget.value)}
          onKeyDown={(event) => {
            // Enter 는 폼 제출이 아니라 이 칸의 더하기다
            if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
              event.preventDefault();
              add();
            }
          }}
          className="h-10 w-72 max-w-full rounded-md border border-border bg-bg px-3"
        />
        <Button variant="secondary" disabled={disabled || full} onClick={add}>
          더하기
        </Button>
      </div>
      {hint && (
        <p id={`${id}-hint`} className="text-xs text-muted-fg">
          {hint}
        </p>
      )}
      <FieldError id={`${id}-error`} messages={messages} />
    </div>
  );
}
