'use client';

import { Button, cx, FieldError, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useState, type FormEvent } from 'react';
import {
  DEFAULT_QUERY,
  hasFilter,
  isOrderId,
  isPreset,
  paymentsHref,
  PRESETS,
  type PaymentQuery,
} from '@/lib/payment/query';
import { PAYMENT_STATUS_LABELS, PAYMENT_STATUSES } from '@/lib/payment/types';

export const ORDER_ID_INVALID = '주문 아이디는 1 이상의 숫자로 입력해 주세요.';

/**
 * 결제 조건 — 상태 · 주문 아이디 · 주문 회신 여부(서버는 AND). 고친 조건은 "검색" 을 눌러야 주소로 간다(주소가 조건의 정본이다).
 * 위의 바로가기는 확인이 필요한 결제 조건을 한 번에 건다. 부모가 `key` 로 지금 주소를 넘겨, 주소가 바뀌면 폼을 새로 시작한다.
 */
export function PaymentFilters({ query }: { query: PaymentQuery }) {
  const id = useId();
  const router = useRouter();
  const [status, setStatus] = useState<string>(query.status ?? '');
  const [orderId, setOrderId] = useState(query.orderId ?? '');
  const [orderSynced, setOrderSynced] = useState(
    query.orderSynced === null ? '' : String(query.orderSynced),
  );
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
      paymentsHref({
        status: PAYMENT_STATUSES.find((item) => item === status) ?? null,
        orderId: trimmed === '' ? null : trimmed,
        orderSynced: orderSynced === 'true' ? true : orderSynced === 'false' ? false : null,
        page: 0,
      }),
    );
  };

  return (
    <div className="flex flex-col gap-3">
      <ul aria-label="바로가기" className="flex flex-wrap gap-2 text-sm">
        {PRESETS.map((preset) => {
          const active = isPreset(query, preset.query);
          return (
            <li key={preset.label}>
              <Link
                href={paymentsHref(preset.query)}
                aria-current={active ? 'page' : undefined}
                className={cx(
                  'inline-block rounded-md border px-3 py-1.5',
                  active ? 'border-fg font-medium' : 'border-border hover:bg-muted',
                )}
              >
                {preset.label}
              </Link>
            </li>
          );
        })}
      </ul>
      <form
        role="search"
        aria-label="결제 조건"
        noValidate
        onSubmit={submit}
        className="flex flex-col gap-4 rounded-md bg-muted/50 p-4"
      >
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
          <div className="flex min-w-0 flex-col gap-1 sm:w-44">
            <label htmlFor={`${id}-status`} className="text-sm font-medium">
              결제 상태
            </label>
            <select
              id={`${id}-status`}
              value={status}
              onChange={(event) => setStatus(event.currentTarget.value)}
              className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
            >
              <option value="">전체</option>
              {PAYMENT_STATUSES.map((value) => (
                <option key={value} value={value}>
                  {PAYMENT_STATUS_LABELS[value]}
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
          <div className="flex min-w-0 flex-col gap-1 sm:w-40">
            <label htmlFor={`${id}-synced`} className="text-sm font-medium">
              주문 회신
            </label>
            <select
              id={`${id}-synced`}
              value={orderSynced}
              onChange={(event) => setOrderSynced(event.currentTarget.value)}
              className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
            >
              <option value="">전체</option>
              <option value="true">수신</option>
              <option value="false">미수신 · 거절</option>
            </select>
          </div>
          <div className="flex items-center gap-4">
            <Button type="submit">검색</Button>
            {hasFilter(query) && (
              <Link
                href={paymentsHref(DEFAULT_QUERY)}
                className="text-sm underline-offset-4 hover:underline"
              >
                조건 초기화
              </Link>
            )}
          </div>
        </div>
        {error && <FieldError id={`${id}-order`} messages={[error]} />}
      </form>
    </div>
  );
}
