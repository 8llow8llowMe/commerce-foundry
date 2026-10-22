'use client';

import { Button, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useState, type FormEvent, type ReactNode } from 'react';
import type { CategoryRow } from '@/lib/category/tree';
import {
  AVAILABILITIES,
  DEFAULT_QUERY,
  hasFilter,
  KEYWORD_MAX,
  productsHref,
  SALE_STATUSES,
  SORT_TYPES,
  type Availability,
  type ProductQuery,
  type SaleStatus,
  type SortType,
} from '@/lib/product/query';

const SALE_STATUS_LABELS: Record<SaleStatus, string> = {
  ON_SALE: '판매중',
  STOP_SELLING: '판매 종료',
};
const AVAILABILITY_LABELS: Record<Availability, string> = {
  ON_SALE: '판매중',
  SOLD_OUT: '품절',
  STOP_SELLING: '판매 종료',
};
const SORT_LABELS: Record<SortType, string> = { NEWEST: '최신순', NAME_ASC: '상품명순' };
const VISIBLE_VALUES = { all: null, true: true, false: false } as const;

/** 어드민 카테고리 트리(숨김 포함)를 편 것. null 은 읽는 중 */
export type Categories = { kind: 'ok'; rows: CategoryRow[] } | { kind: 'failed' } | null;

/**
 * 상품 목록 조건(#208). 고친 조건은 "검색" 을 눌러야 주소로 간다 — 주소가 조건의 정본이다(새로고침 · 뒤로 가기 · 공유).
 * 부모가 `key` 로 지금 주소를 넘겨, 주소가 바뀌면(뒤로 가기 · 페이지 이동) 폼을 새로 시작한다.
 * 카테고리는 어드민 카테고리 트리(숨김 포함)에서 고른다 — 부모가 화면에 한 번 읽어 넘긴다.
 * 못 읽어도 상품 목록은 그대로 쓴다(카테고리 조건만 못 고른다).
 */
export function ProductFilters({
  query,
  categories,
}: {
  query: ProductQuery;
  categories: Categories;
}) {
  const id = useId();
  const router = useRouter();
  const [draft, setDraft] = useState<ProductQuery>(query);
  const set = (patch: Partial<ProductQuery>) => setDraft((current) => ({ ...current, ...patch }));

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    router.push(productsHref({ ...draft, keyword: draft.keyword.trim(), page: 0 }));
  };

  const rows = categories?.kind === 'ok' ? categories.rows : [];
  // 주소의 카테고리가 트리에 없으면(지워졌거나 아직 못 읽음) 그 값을 옵션으로 남겨 실제 조건을 보인다
  const unknownCategory =
    draft.categoryId !== null && !rows.some((row) => row.node.categoryId === draft.categoryId);

  return (
    <form
      role="search"
      aria-label="상품 조건"
      onSubmit={submit}
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-4"
    >
      <TextField
        label="상품명"
        type="search"
        maxLength={KEYWORD_MAX}
        value={draft.keyword}
        onChange={(event) => set({ keyword: event.currentTarget.value })}
      />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Select
          id={`${id}-category`}
          label="카테고리 (하위 포함)"
          value={draft.categoryId ?? ''}
          onChange={(value) => set({ categoryId: value === '' ? null : value })}
          hint={categories?.kind === 'failed' ? '카테고리 목록을 불러오지 못했습니다.' : null}
        >
          <option value="">전체</option>
          {unknownCategory && (
            <option value={draft.categoryId ?? ''}>
              {categories === null ? '불러오는 중…' : `카테고리 ${draft.categoryId}`}
            </option>
          )}
          {rows.map((row) => (
            <option key={row.node.categoryId} value={row.node.categoryId}>
              {row.path.join(' > ')}
              {row.hiddenBy ? ' (숨김)' : ''}
            </option>
          ))}
        </Select>
        <Select
          id={`${id}-sale`}
          label="판매 상태"
          value={draft.saleStatus ?? ''}
          onChange={(value) =>
            set({ saleStatus: SALE_STATUSES.find((item) => item === value) ?? null })
          }
        >
          <option value="">전체</option>
          {SALE_STATUSES.map((value) => (
            <option key={value} value={value}>
              {SALE_STATUS_LABELS[value]}
            </option>
          ))}
        </Select>
        <Select
          id={`${id}-visible`}
          label="노출"
          value={draft.visible === null ? 'all' : String(draft.visible)}
          onChange={(value) =>
            set({ visible: VISIBLE_VALUES[value as keyof typeof VISIBLE_VALUES] ?? null })
          }
        >
          <option value="all">전체</option>
          <option value="true">노출</option>
          <option value="false">숨김</option>
        </Select>
        <Select
          id={`${id}-availability`}
          label="판매 가능 (재고 반영)"
          value={draft.availability ?? ''}
          onChange={(value) =>
            set({ availability: AVAILABILITIES.find((item) => item === value) ?? null })
          }
        >
          <option value="">전체</option>
          {AVAILABILITIES.map((value) => (
            <option key={value} value={value}>
              {AVAILABILITY_LABELS[value]}
            </option>
          ))}
        </Select>
        <Select
          id={`${id}-sort`}
          label="정렬"
          value={draft.sortType}
          onChange={(value) =>
            set({ sortType: SORT_TYPES.find((item) => item === value) ?? DEFAULT_QUERY.sortType })
          }
        >
          {SORT_TYPES.map((value) => (
            <option key={value} value={value}>
              {SORT_LABELS[value]}
            </option>
          ))}
        </Select>
      </div>
      <div className="flex items-center gap-4">
        <Button type="submit">검색</Button>
        {hasFilter(query) && (
          <Link
            href={productsHref({ ...DEFAULT_QUERY, sortType: query.sortType })}
            className="text-sm underline-offset-4 hover:underline"
          >
            조건 초기화
          </Link>
        )}
      </div>
    </form>
  );
}

function Select({
  id,
  label,
  value,
  onChange,
  hint,
  children,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: string | null;
  children: ReactNode;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      <select
        id={id}
        value={value}
        aria-describedby={hint ? `${id}-hint` : undefined}
        onChange={(event) => onChange(event.currentTarget.value)}
        className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
      >
        {children}
      </select>
      {hint && (
        <p id={`${id}-hint`} className="text-xs text-muted-fg">
          {hint}
        </p>
      )}
    </div>
  );
}
