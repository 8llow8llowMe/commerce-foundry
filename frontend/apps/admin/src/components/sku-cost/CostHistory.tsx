'use client';

import { apiFetch, formatMoney, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useEffect, useId, useState } from 'react';
import type { AccessDenied } from '@/lib/api-errors';
import { formatDateTime } from '@/lib/display/text';
import { readFailure } from '@/lib/sku-cost/errors';
import {
  COST_TYPE_LABELS,
  COST_TYPES,
  skuCostHistoriesPath,
  type HistoryQuery as Query,
  type SkuCostItem,
} from '@/lib/sku-cost/types';

type Loaded =
  | { query: Query; kind: 'ok'; data: PageResponse<SkuCostItem> }
  | { query: Query; kind: 'failed'; message: string };

/** 이력 표(표시만). 기록한 사람은 내부 회원 아이디뿐이라 "관리자" 로 보인다(배송비 이력 #305 와 같은 규칙) */
export function CostHistoryTable({ items }: { items: SkuCostItem[] }) {
  return (
    <div className="relative overflow-x-auto">
      <table className="w-full min-w-[40rem] text-left text-sm">
        <thead className="text-muted-fg">
          <tr>
            <th className="py-2 pr-4 font-medium">적용 시작</th>
            <th className="py-2 pr-4 font-medium">유형</th>
            <th className="py-2 pr-4 text-right font-medium">금액</th>
            <th className="py-2 pr-4 font-medium">사유</th>
            <th className="py-2 pr-4 font-medium">기록</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.costId} className="align-top odd:bg-bg">
              <td className="py-2 pr-4 whitespace-nowrap">{formatDateTime(item.effectiveFrom)}</td>
              <td className="py-2 pr-4 whitespace-nowrap">{item.costType.name}</td>
              <td className="py-2 pr-4 text-right whitespace-nowrap tabular-nums">
                {formatMoney(item.amount)}
              </td>
              <td className="py-2 pr-4 break-all">{item.reason}</td>
              <td className="py-2 pr-4 whitespace-nowrap text-muted-fg">
                {formatDateTime(item.createdAt)} · 관리자
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * SKU 하나의 원가 이력(#395) — `GET /api/v1/admin/skus/{skuId}/costs/histories`, 적용 시작 내림차순 페이지(20개).
 * 유형으로 거를 수 있다. 추가 · 다시 읽기 뒤에는 부모가 `key` 를 바꿔 첫 페이지부터 다시 읽게 한다.
 */
export function CostHistory({
  skuId,
  onDenied,
}: {
  skuId: string;
  onDenied: (denied: AccessDenied) => void;
}) {
  const id = useId();
  const [query, setQuery] = useState<Query>({ costType: '', page: 0 });
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [retry, setRetry] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 `loaded.query !== query` 로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<SkuCostItem>>(skuCostHistoriesPath(skuId, query)).then(
      (data) => alive && setLoaded({ query, kind: 'ok', data }),
      (cause: unknown) => {
        if (!alive) return;
        const failure = readFailure(cause);
        if (failure.kind === 'access') onDenied(failure.denied);
        else
          setLoaded({
            query,
            kind: 'failed',
            message: failure.kind === 'notFound' ? 'SKU 를 찾을 수 없습니다.' : failure.message,
          });
      },
    );
    return () => {
      alive = false;
    };
  }, [skuId, query, retry, onDenied]);

  const current = loaded?.query === query ? loaded : null;
  const page = query.page;

  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`}>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h3 id={`${id}-title`} className="font-semibold">
          원가 이력
        </h3>
        <div className="flex items-center gap-2">
          <label htmlFor={`${id}-type`} className="text-sm text-muted-fg">
            유형
          </label>
          <select
            id={`${id}-type`}
            value={query.costType}
            onChange={(event) =>
              setQuery({ costType: event.currentTarget.value as Query['costType'], page: 0 })
            }
            className="h-9 rounded-md border border-border bg-bg px-2 text-sm"
          >
            <option value="">전체</option>
            {COST_TYPES.map((costType) => (
              <option key={costType} value={costType}>
                {COST_TYPE_LABELS[costType]}
              </option>
            ))}
          </select>
        </div>
      </div>
      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{current.message}</p>
          <Button variant="secondary" onClick={() => setRetry((n) => n + 1)}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' && current.data.contents.length === 0 && (
        <p className="text-sm text-muted-fg">
          {page > 0 ? '이 페이지에는 이력이 없습니다.' : '원가 이력이 없습니다.'}
        </p>
      )}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <CostHistoryTable items={current.data.contents} />
      )}
      {current?.kind === 'ok' && (current.data.totalPages > 1 || page > 0) && (
        <div className="flex items-center gap-3 text-sm">
          <Button
            variant="secondary"
            disabled={page === 0}
            onClick={() => setQuery({ ...query, page: page - 1 })}
          >
            이전
          </Button>
          <span>
            {page + 1} / {Math.max(current.data.totalPages, 1)}
          </span>
          <Button
            variant="secondary"
            disabled={page + 1 >= current.data.totalPages}
            onClick={() => setQuery({ ...query, page: page + 1 })}
          >
            다음
          </Button>
        </div>
      )}
    </section>
  );
}
