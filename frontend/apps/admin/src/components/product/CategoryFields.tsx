'use client';

import { Checkbox, FieldError } from '@cf/ui';
import { useId } from 'react';
import type { CategoryRow } from '@/lib/category/tree';
import { CATEGORY_IDS_MAX } from '@/lib/product/create';

const pathOf = (row: CategoryRow) => `${row.path.join(' > ')}${row.hiddenBy ? ' (숨김)' : ''}`;

/**
 * 대표 카테고리 + 함께 진열할 카테고리(#29) — 상품 등록과 카테고리 수정이 같이 쓴다.
 * 진열 목록은 대표를 뺀 값이다(대표 칸은 체크된 채 잠긴다). 오류 키는 `categoryId` · `categoryIds`.
 */
export function CategoryFields({
  rows,
  categoryId,
  categoryIds,
  onChange,
  errorsOf,
}: {
  rows: readonly CategoryRow[];
  categoryId: string;
  categoryIds: string[];
  onChange: (value: { categoryId?: string; categoryIds?: string[] }) => void;
  errorsOf: (key: string) => string[];
}) {
  const id = useId();
  const primaryErrors = errorsOf('categoryId');
  return (
    <>
      <div className="flex flex-col gap-1">
        <label htmlFor={`${id}-category`} className="text-sm font-medium">
          대표 카테고리
        </label>
        <select
          id={`${id}-category`}
          value={categoryId}
          aria-invalid={primaryErrors.length > 0 || undefined}
          aria-describedby={primaryErrors.length > 0 ? `${id}-category-error` : undefined}
          onChange={(event) => onChange({ categoryId: event.currentTarget.value })}
          className="h-10 max-w-md rounded-md border border-border bg-bg px-3"
        >
          <option value="">고르세요</option>
          {rows.map((row) => (
            <option key={row.node.categoryId} value={row.node.categoryId}>
              {pathOf(row)}
            </option>
          ))}
        </select>
        <FieldError id={`${id}-category-error`} messages={primaryErrors} />
      </div>
      <fieldset className="flex flex-col gap-1">
        <legend className="text-sm font-medium">
          함께 진열할 카테고리 (선택, 대표 포함 {CATEGORY_IDS_MAX}개까지)
        </legend>
        <div className="max-h-60 overflow-y-auto rounded-md border border-border p-3">
          {rows.map((row) => {
            const primary = row.node.categoryId === categoryId;
            return (
              <Checkbox
                key={row.node.categoryId}
                label={primary ? `${pathOf(row)} — 대표` : pathOf(row)}
                checked={primary || categoryIds.includes(row.node.categoryId)}
                disabled={primary}
                onChange={(event) => {
                  const checked = event.currentTarget.checked;
                  onChange({
                    categoryIds: checked
                      ? [...categoryIds, row.node.categoryId]
                      : categoryIds.filter((item) => item !== row.node.categoryId),
                  });
                }}
                className="py-1"
              />
            );
          })}
        </div>
        <FieldError messages={errorsOf('categoryIds')} />
      </fieldset>
    </>
  );
}
