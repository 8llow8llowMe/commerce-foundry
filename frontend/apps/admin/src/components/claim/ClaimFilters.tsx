'use client';

import { Button, FieldError, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useState, type FormEvent } from 'react';
import { claimsHref, DEFAULT_QUERY, hasFilter, type ClaimQuery } from '@/lib/claim/query';
import {
  CLAIM_STATUS_LABELS,
  CLAIM_STATUSES,
  CLAIM_TYPE_LABELS,
  CLAIM_TYPES,
} from '@/lib/claim/types';
import { isOrderId } from '@/lib/payment/query';
import { ORDER_ID_INVALID } from '@/components/payment/PaymentFilters';

/**
 * 클레임 조건 — 유형 · 상태 · 주문 아이디(서버는 AND). 고친 조건은 "검색" 을 눌러야 주소로 간다(주소가 조건의 정본이다).
 * 부모가 `key` 로 지금 주소를 넘겨, 주소가 바뀌면 폼을 새로 시작한다.
 */
export function ClaimFilters({ query }: { query: ClaimQuery }) {
  const id = useId();
  const router = useRouter();
  const [type, setType] = useState<string>(query.type ?? '');
  const [status, setStatus] = useState<string>(query.status ?? '');
  const [orderId, setOrderId] = useState(query.orderId ?? '');
  const [error, setError] = useState<string | null>(null);

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = orderId.trim();
    if (trimmed !== '' && !isOrderId(trimmed)) {
      setError(ORDER_ID_INVALID);
      return;
    }
    setError(null);
    router.push(
      claimsHref({
        type: CLAIM_TYPES.find((item) => item === type) ?? null,
        status: CLAIM_STATUSES.find((item) => item === status) ?? null,
        orderId: trimmed === '' ? null : trimmed,
        page: 0,
      }),
    );
  };

  return (
    <form
      role="search"
      aria-label="클레임 조건"
      noValidate
      onSubmit={submit}
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-4"
    >
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
        <div className="flex min-w-0 flex-col gap-1 sm:w-36">
          <label htmlFor={`${id}-type`} className="text-sm font-medium">
            유형
          </label>
          <select
            id={`${id}-type`}
            value={type}
            onChange={(event) => setType(event.currentTarget.value)}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">전체</option>
            {CLAIM_TYPES.map((value) => (
              <option key={value} value={value}>
                {CLAIM_TYPE_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
        <div className="flex min-w-0 flex-col gap-1 sm:w-36">
          <label htmlFor={`${id}-status`} className="text-sm font-medium">
            상태
          </label>
          <select
            id={`${id}-status`}
            value={status}
            onChange={(event) => setStatus(event.currentTarget.value)}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">전체</option>
            {CLAIM_STATUSES.map((value) => (
              <option key={value} value={value}>
                {CLAIM_STATUS_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
        <TextField
          label="주문 아이디"
          inputMode="numeric"
          value={orderId}
          onChange={(event) => setOrderId(event.currentTarget.value)}
          className="sm:w-56"
        />
        <div className="flex items-center gap-4">
          <Button type="submit">검색</Button>
          {hasFilter(query) && (
            <Link
              href={claimsHref(DEFAULT_QUERY)}
              className="text-sm underline-offset-4 hover:underline"
            >
              조건 초기화
            </Link>
          )}
        </div>
      </div>
      {error && <FieldError id={`${id}-order`} messages={[error]} />}
    </form>
  );
}
