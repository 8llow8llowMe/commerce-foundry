'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useEffect, useRef, useState } from 'react';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { STOCKS_PATH, type StockAdjustmentHistoryItem } from '@/lib/stock/types';

const PAGE_SIZE = 20;

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

type Loaded =
  | { page: number; kind: 'ok'; data: PageResponse<StockAdjustmentHistoryItem> }
  | { page: number; kind: 'failed' };

const managedText = (managed: boolean) => (managed ? '관리' : '무제한');

/**
 * SKU 하나의 조정 이력(#188) — `GET /api/v1/admin/stocks/{skuId}/adjustments`, 최신순 페이지.
 * 조정에 성공하면 부모가 `key` 를 바꿔 첫 페이지부터 다시 읽게 한다.
 */
export function AdjustmentHistory({
  skuId,
  label,
  onDenied,
}: {
  skuId: string;
  label: string;
  onDenied: (denied: AccessDenied) => void;
}) {
  const [page, setPage] = useState(0);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [retry, setRetry] = useState(0);
  const titleRef = useRef<HTMLHeadingElement>(null);
  // 열리면 제목으로 포커스를 옮긴다 — 누른 이력 버튼은 표 안에 있어 패널과 멀다
  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 `loaded.page !== page` 로 그린다
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<StockAdjustmentHistoryItem>>(
      `${STOCKS_PATH}/${encodeURIComponent(skuId)}/adjustments?page=${page}&size=${PAGE_SIZE}`,
    ).then(
      (data) => alive && setLoaded({ page, kind: 'ok', data }),
      (cause: unknown) => {
        if (!alive) return;
        const denied = accessDeniedOf(cause);
        if (denied) onDenied(denied);
        else setLoaded({ page, kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [skuId, page, retry, onDenied]);

  const current = loaded?.page === page ? loaded : null;

  return (
    <section
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-5"
      aria-label={`${label} 조정 이력`}
    >
      <h2 ref={titleRef} tabIndex={-1} className="font-semibold outline-none">
        조정 이력 — {label}
      </h2>
      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => setRetry((n) => n + 1)}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' && current.data.contents.length === 0 && (
        <p className="text-sm text-muted-fg">조정 이력이 없습니다.</p>
      )}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[40rem] text-left text-sm">
              <thead className="text-muted-fg">
                <tr>
                  <th className="py-2 pr-4 font-medium">시각</th>
                  <th className="py-2 pr-4 font-medium">사유</th>
                  <th className="py-2 pr-4 font-medium">수량</th>
                  <th className="py-2 pr-4 font-medium">관리</th>
                  <th className="py-2 pr-4 font-medium">메모</th>
                  <th className="py-2 font-medium">관리자</th>
                </tr>
              </thead>
              <tbody>
                {current.data.contents.map((item) => (
                  <tr key={item.id} className="align-top odd:bg-bg">
                    <td className="py-2 pr-4 whitespace-nowrap">
                      {dateTime.format(new Date(item.createdAt))}
                    </td>
                    <td className="py-2 pr-4 whitespace-nowrap">{item.reason.name}</td>
                    <td className="py-2 pr-4 whitespace-nowrap">
                      {item.beforeQuantity} → {item.afterQuantity}{' '}
                      <span className="text-muted-fg">
                        ({item.delta > 0 ? `+${item.delta}` : item.delta})
                      </span>
                    </td>
                    <td className="py-2 pr-4 whitespace-nowrap">
                      {item.beforeStockManaged === item.afterStockManaged
                        ? managedText(item.afterStockManaged)
                        : `${managedText(item.beforeStockManaged)} → ${managedText(item.afterStockManaged)}`}
                    </td>
                    <td className="py-2 pr-4 break-all">{item.memo ?? ''}</td>
                    <td className="py-2 whitespace-nowrap text-muted-fg">{item.adminMemberId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {current.data.totalPages > 1 && (
            <div className="flex items-center gap-3 text-sm">
              <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
                이전
              </Button>
              <span>
                {page + 1} / {current.data.totalPages}
              </span>
              <Button
                variant="secondary"
                disabled={page + 1 >= current.data.totalPages}
                onClick={() => setPage(page + 1)}
              >
                다음
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
