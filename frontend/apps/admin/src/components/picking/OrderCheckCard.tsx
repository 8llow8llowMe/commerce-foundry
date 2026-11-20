'use client';

import { Button, FieldError } from '@cf/ui';
import { useId } from 'react';
import { COUNT_MAX, type PickingBatch, type PickingOrder } from '@/lib/picking/types';
import {
  latestCheckOf,
  orderCheckState,
  orderHeadingId,
  type OrderCheckState,
} from '@/lib/picking/workbench';

const STATE_LABELS: Record<OrderCheckState, string> = {
  checked: '검수 완료',
  failed: '검수 실패',
  pending: '미검수',
};

const STATE_CLASSES: Record<OrderCheckState, string> = {
  checked: 'bg-accent text-accent-fg',
  failed: 'border border-danger text-danger',
  pending: 'bg-muted text-fg',
};

/**
 * 주문 하나의 포장 검수 카드 — 주문 수량 옆에 실제로 센 수량을 넣고 검수한다.
 * 바코드가 없어도 옵션 이름 · ID 로 맞춰 보고, 주문에 없는 옵션이 들어갔으면 "다른 옵션" 에서 묶음의 옵션을 골라 같이 보낸다(서버가 `WRONG_SKU` 로 판정).
 * 성공 여부는 서버 응답이 정본이다. 입력은 이 화면 메모리에만 두고 브라우저 저장소에 남기지 않는다.
 */
export function OrderCheckCard({
  batch,
  order,
  labels,
  counts,
  extraSkuIds,
  errors,
  busy,
  onCount,
  onAddExtra,
  onCheck,
}: {
  batch: PickingBatch;
  order: PickingOrder;
  labels: Readonly<Record<string, string>>;
  /** 옵션 ID → 칸 글자 */
  counts: Readonly<Record<string, string>>;
  /** 주문에 없는데 들어간 옵션 */
  extraSkuIds: readonly string[];
  /** 옵션 ID 또는 `form` → 문구 */
  errors: Readonly<Record<string, string>>;
  busy: boolean;
  onCount: (skuId: string, text: string) => void;
  onAddExtra: (skuId: string) => void;
  onCheck: () => void;
}) {
  const id = useId();
  const state = orderCheckState(batch, order.orderId);
  const latest = latestCheckOf(batch, order.orderId);
  const ordered = new Set(order.quantities.map((quantity) => quantity.skuId));
  const addable = batch.pickingQuantities
    .map((quantity) => quantity.skuId)
    .filter((skuId) => !ordered.has(skuId) && !extraSkuIds.includes(skuId));
  const rows = [
    ...order.quantities.map((quantity) => ({ skuId: quantity.skuId, ordered: quantity.quantity })),
    ...extraSkuIds.map((skuId) => ({ skuId, ordered: 0 })),
  ];

  return (
    <li
      aria-labelledby={orderHeadingId(order.orderId)}
      className="flex flex-col gap-3 rounded-md border border-border p-4"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={orderHeadingId(order.orderId)} tabIndex={-1} className="font-medium">
          주문 {order.orderId}
        </h3>
        <span className={`rounded px-2 py-0.5 text-xs font-medium ${STATE_CLASSES[state]}`}>
          {STATE_LABELS[state]}
        </span>
      </div>
      {latest && !latest.successful && (
        <p className="text-sm text-danger">
          마지막 검수: {latest.reason.name}
          {latest.reason.description ? ` — ${latest.reason.description}` : ''}
        </p>
      )}
      {state === 'checked' ? (
        <p className="text-sm text-muted-fg">
          포장 검수를 마쳤습니다. 송장 등록 때 주문 상태를 다시 확인합니다.
        </p>
      ) : (
        <>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-muted-fg">
                <th scope="col" className="py-2 pr-3 font-normal">
                  상품 옵션
                </th>
                <th scope="col" className="w-20 py-2 pr-3 text-right font-normal">
                  주문 수량
                </th>
                <th scope="col" className="w-28 py-2 font-normal">
                  센 수량
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const inputId = `${id}-${row.skuId}`;
                const error = errors[row.skuId];
                return (
                  <tr key={row.skuId} className="border-b border-border align-top">
                    <td className="py-2 pr-3">
                      <label htmlFor={inputId} className="flex flex-col">
                        <span>{labels[row.skuId] ?? '이름을 불러오지 못한 옵션'}</span>
                        <span className="text-xs text-muted-fg">옵션 ID {row.skuId}</span>
                        {row.ordered === 0 && (
                          <span className="text-xs text-danger">주문에 없는 옵션</span>
                        )}
                      </label>
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">{row.ordered}</td>
                    <td className="py-2">
                      <input
                        id={inputId}
                        inputMode="numeric"
                        autoComplete="off"
                        maxLength={String(COUNT_MAX).length}
                        value={counts[row.skuId] ?? ''}
                        disabled={busy}
                        aria-invalid={error ? true : undefined}
                        aria-describedby={error ? `${inputId}-error` : undefined}
                        onChange={(event) => onCount(row.skuId, event.currentTarget.value)}
                        className="h-11 w-full min-w-0 rounded-md border border-border bg-bg px-3 text-right tabular-nums"
                      />
                      <FieldError id={`${inputId}-error`} messages={error ? [error] : []} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
            {addable.length > 0 ? (
              <div className="flex min-w-0 flex-col gap-1">
                <label htmlFor={`${id}-extra`} className="text-sm">
                  주문에 없는 옵션이 들어 있으면 고르세요
                </label>
                <select
                  id={`${id}-extra`}
                  value=""
                  disabled={busy}
                  onChange={(event) => {
                    if (event.currentTarget.value) onAddExtra(event.currentTarget.value);
                  }}
                  className="h-11 min-w-0 rounded-md border border-border bg-bg px-3"
                >
                  <option value="">다른 옵션 고르기</option>
                  {addable.map((skuId) => (
                    <option key={skuId} value={skuId}>
                      {labels[skuId] ?? `옵션 ID ${skuId}`}
                    </option>
                  ))}
                </select>
              </div>
            ) : (
              <span />
            )}
            <Button onClick={onCheck} disabled={busy} className="h-11">
              {busy ? '검수 중…' : '이 주문 검수'}
            </Button>
          </div>
          <FieldError messages={errors.form ? [errors.form] : []} />
        </>
      )}
    </li>
  );
}
