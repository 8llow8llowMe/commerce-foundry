'use client';

import { Button, FieldError, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useState, type FormEvent } from 'react';
import { DEFAULT_QUERY, hasFilter, isDate, ordersHref, type OrderQuery } from '@/lib/order/query';
import { ORDER_STATUS_LABELS, ORDER_STATUSES } from '@/lib/order/types';

export const RANGE_INVERTED = '시작일이 종료일보다 늦습니다.';

/**
 * 주문 조건 — 상태 · 주문일(한국 날짜, 양 끝 포함). 고친 조건은 "검색" 을 눌러야 주소로 간다(주소가 조건의 정본이다).
 * 부모가 `key` 로 지금 주소를 넘겨, 주소가 바뀌면(뒤로 가기 · 페이지 이동) 폼을 새로 시작한다.
 */
export function OrderFilters({ query }: { query: OrderQuery }) {
  const id = useId();
  const router = useRouter();
  const [status, setStatus] = useState<string>(query.status ?? '');
  const [from, setFrom] = useState(query.from ?? '');
  const [to, setTo] = useState(query.to ?? '');
  const [error, setError] = useState<string | null>(null);

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const fromDate = isDate(from) ? from : null;
    const toDate = isDate(to) ? to : null;
    if (fromDate && toDate && fromDate > toDate) {
      setError(RANGE_INVERTED);
      return;
    }
    setError(null);
    router.push(
      ordersHref({
        status: ORDER_STATUSES.find((item) => item === status) ?? null,
        from: fromDate,
        to: toDate,
        page: 0,
      }),
    );
  };

  return (
    <form
      role="search"
      aria-label="주문 조건"
      data-tour="order-filters"
      noValidate
      onSubmit={submit}
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-4"
    >
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
        <div className="flex min-w-0 flex-col gap-1 sm:w-44">
          <label htmlFor={`${id}-status`} className="text-sm font-medium">
            주문 상태
          </label>
          <select
            id={`${id}-status`}
            value={status}
            onChange={(event) => setStatus(event.currentTarget.value)}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">전체</option>
            {ORDER_STATUSES.map((value) => (
              <option key={value} value={value}>
                {ORDER_STATUS_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
        <TextField
          label="주문일 시작"
          type="date"
          value={from}
          onChange={(event) => setFrom(event.currentTarget.value)}
          className="sm:w-44"
        />
        <TextField
          label="주문일 끝"
          type="date"
          value={to}
          onChange={(event) => setTo(event.currentTarget.value)}
          className="sm:w-44"
        />
        <div className="flex items-center gap-4">
          <Button type="submit">검색</Button>
          {hasFilter(query) && (
            <Link
              href={ordersHref(DEFAULT_QUERY)}
              className="text-sm underline-offset-4 hover:underline"
            >
              조건 초기화
            </Link>
          )}
        </div>
      </div>
      {error && <FieldError id={`${id}-range`} messages={[error]} />}
    </form>
  );
}
