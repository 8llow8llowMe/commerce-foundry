'use client';

import { apiFetch } from '@cf/api-client';
import { Button, Checkbox, FieldError, TextField } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import { categoryRequest, categorySaveFailure, type CategoryForm } from '@/lib/category/form';
import type { CategoryRow } from '@/lib/category/tree';
import { CATEGORIES_PATH, type Category } from '@/lib/category/types';

/**
 * 카테고리 만들기 · 고치기 폼(#206). 만들기는 `POST`, 고치기는 `PUT /{categoryId}`(전체 교체) — 본문은 같다.
 * 상위 카테고리는 3단계를 넘지 않고 자기 자신 · 하위가 아닌 것만 고를 수 있다(`parentCandidates`, 정본은 서버).
 * 부모가 `key` 로 대상을 넘겨, 대상이 바뀌면 폼을 새로 시작한다.
 */
export function CategoryEditor({
  title,
  categoryId,
  initial,
  candidates,
  onSaved,
  onReload,
  onDenied,
  onCancel,
}: {
  title: string;
  /** 고칠 카테고리. null 이면 새로 만든다 */
  categoryId: string | null;
  initial: CategoryForm;
  candidates: CategoryRow[];
  onSaved: (saved: Category) => void;
  /** 대상이 없어졌거나(404) 결과를 모름(502 · 504) — 부모가 목록을 다시 읽고 안내한다 */
  onReload: (notice: string) => void;
  onDenied: (denied: AccessDenied) => void;
  onCancel: () => void;
}) {
  const id = useId();
  const titleRef = useRef<HTMLHeadingElement>(null);
  // 열리면 제목으로 포커스를 옮긴다 — 누른 버튼(목록의 줄)과 폼이 떨어져 있어서다
  useEffect(() => {
    titleRef.current?.focus();
  }, []);
  const [form, setForm] = useState<CategoryForm>(initial);
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const errorsOf = (key: string) => errors[key] ?? [];
  const set = (patch: Partial<CategoryForm>) => setForm((current) => ({ ...current, ...patch }));

  const submit = async () => {
    if (saving) return;
    const built = categoryRequest(form);
    if (!built.ok) {
      setErrors(built.errors);
      return;
    }
    setSaving(true);
    setErrors({});
    try {
      const saved = await apiFetch<Category>(
        categoryId === null
          ? CATEGORIES_PATH
          : `${CATEGORIES_PATH}/${encodeURIComponent(categoryId)}`,
        { method: categoryId === null ? 'POST' : 'PUT', json: built.request },
      );
      onSaved(saved);
    } catch (cause) {
      setSaving(false);
      const failure = categorySaveFailure(cause);
      if (failure.kind === 'access') onDenied(failure.denied);
      else if (failure.kind === 'reload') onReload(failure.notice);
      else setErrors(failure.errors);
    }
  };

  const parentErrors = errorsOf('parentId');

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
        {title}
      </h2>
      <fieldset disabled={saving} className="flex min-w-0 flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label htmlFor={`${id}-parent`} className="text-sm font-medium">
            상위 카테고리
          </label>
          <select
            id={`${id}-parent`}
            value={form.parentId}
            aria-invalid={parentErrors.length > 0 || undefined}
            aria-describedby={parentErrors.length > 0 ? `${id}-parent-error` : undefined}
            onChange={(event) => set({ parentId: event.currentTarget.value })}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">없음 (최상위)</option>
            {candidates.map((row) => (
              <option key={row.node.categoryId} value={row.node.categoryId}>
                {row.path.join(' > ')}
              </option>
            ))}
          </select>
          <FieldError id={`${id}-parent-error`} messages={parentErrors} />
        </div>
        {/* 길이 · 금지 문자는 서버가 정본이다(코드 포인트 기준이라 maxLength 로 자르지 않는다) */}
        <TextField
          label="이름"
          value={form.name}
          onChange={(event) => set({ name: event.currentTarget.value })}
          errors={errorsOf('name')}
        />
        <div className="flex flex-col gap-1">
          <TextField
            label="노출 순서"
            inputMode="numeric"
            value={form.displayOrder}
            onChange={(event) => set({ displayOrder: event.currentTarget.value })}
            errors={errorsOf('displayOrder')}
          />
          <p className="text-xs text-muted-fg">
            같은 상위 안에서 작은 수가 먼저 나옵니다. 같으면 먼저 만든 카테고리가 앞입니다.
          </p>
        </div>
        <div className="flex flex-col gap-1">
          <Checkbox
            label="고객 메뉴에 노출"
            checked={form.visible}
            onChange={(event) => set({ visible: event.currentTarget.checked })}
            errors={errorsOf('visible')}
          />
          <p className="text-xs text-muted-fg">
            숨기면 하위 카테고리까지 고객 메뉴에서 빠집니다. 상품 노출은 바뀌지 않습니다.
          </p>
        </div>
      </fieldset>
      <FieldError messages={errorsOf('form')} />
      <div className="flex gap-2">
        <Button type="submit" disabled={saving}>
          {saving ? '저장 중…' : '저장'}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={saving}>
          취소
        </Button>
      </div>
    </form>
  );
}
