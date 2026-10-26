'use client';

import { ApiError, apiFetch, formatMoney } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { skuLabel } from '@/lib/stock/adjustment';
import { fetchStocks } from '@/lib/stock/api';
import type { ProductForStock, StockAdmin } from '@/lib/stock/types';
import { AdjustmentHistory } from './AdjustmentHistory';
import { AdjustPanel } from './AdjustPanel';

type Status =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'loadFailed' }
  | { kind: 'ready'; product: ProductForStock; stocks: Map<string, StockAdmin> };

type Selection = { skuId: string; mode: 'adjust' | 'history' } | null;

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/**
 * 상품 하나의 SKU 별 재고(#188). 상품 · 옵션은 고객 상품 상세에서, 재고는 어드민 재고 API 에서 읽는다.
 * 재고 행이 없는 SKU 는 서버가 품절(관리 · 0)로 돌려준다. 조정 · 이력은 표 아래 패널에서 한 SKU 씩 연다.
 */
export function ProductStocks({ productId }: { productId: string }) {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [selection, setSelection] = useState<Selection>(null);
  /** 조정 결과 안내 — 그 SKU 패널이 열려 있을 때만 보인다(다른 SKU 를 연 뒤 늦게 온 결과가 엉뚱한 패널 위에 뜨지 않게) */
  const [notice, setNotice] = useState<{ skuId: string; message: string } | null>(null);

  const denied = useCallback(
    (kind: AccessDenied) => setStatus({ kind: 'denied', denied: kind }),
    [],
  );

  // 상태는 응답 콜백에서만 바꾼다 — 마운트 effect 가 부른다
  const load = useCallback(
    () =>
      apiFetch<ProductForStock>(`/api/v1/products/${encodeURIComponent(productId)}`)
        .then(async (product) => {
          const stocks = await fetchStocks(product.skus.map((sku) => sku.skuId));
          setStatus({ kind: 'ready', product, stocks });
        })
        .catch((cause: unknown) => {
          const kind = accessDeniedOf(cause);
          if (kind) setStatus({ kind: 'denied', denied: kind });
          else if (cause instanceof ApiError && cause.kind === 'notFound')
            setStatus({ kind: 'notFound' });
          else setStatus({ kind: 'loadFailed' });
        }),
    [productId],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const replaceStock = (stock: StockAdmin) =>
    setStatus((current) =>
      current.kind === 'ready'
        ? { ...current, stocks: new Map(current.stocks).set(stock.skuId, stock) }
        : current,
    );

  /** 충돌 · 결과 모름 — 그 SKU 만 다시 읽고 안내한다 */
  const refreshSku = (skuId: string, message: string) =>
    fetchStocks([skuId]).then(
      (stocks) => {
        const stock = stocks.get(skuId);
        if (stock) replaceStock(stock);
        setNotice({ skuId, message });
      },
      (cause: unknown) => {
        const kind = accessDeniedOf(cause);
        if (kind) denied(kind);
        else setNotice({ skuId, message: LOAD_FAILED });
      },
    );

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href="/stocks" className="text-sm text-muted-fg underline underline-offset-4">
          상품 목록
        </Link>
        <h1 className="text-xl font-semibold">
          {status.kind === 'ready' ? status.product.name : '재고'}
        </h1>
      </div>

      {status.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {status.kind === 'denied' && <AccessNotice denied={status.denied} />}
      {status.kind === 'notFound' && (
        <p className="text-sm text-muted-fg">
          상품을 찾을 수 없습니다. 숨긴 상품은 어드민 상품 관리가 생기기 전까지 여기서 볼 수
          없습니다.
        </p>
      )}
      {status.kind === 'loadFailed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button
            variant="secondary"
            onClick={() => {
              setStatus({ kind: 'loading' });
              void load();
            }}
          >
            다시 불러오기
          </Button>
        </div>
      )}

      {status.kind === 'ready' && (
        <>
          {/* relative — 표 머리의 sr-only(absolute)가 스크롤 상자 밖으로 나가 화면을 가로로 넓히지 않게 */}
          <div data-tour="stock-table" className="relative overflow-x-auto">
            <table className="w-full min-w-[44rem] text-left text-sm">
              <thead className="text-muted-fg">
                <tr>
                  <th className="py-2 pr-4 font-medium">옵션</th>
                  <th className="py-2 pr-4 font-medium">판매가</th>
                  <th className="py-2 pr-4 font-medium">재고 관리</th>
                  <th className="py-2 pr-4 text-right font-medium">판매 가능</th>
                  <th className="py-2 pr-4 text-right font-medium">결제 대기 선점</th>
                  <th className="py-2 pr-4 font-medium">마지막 변경</th>
                  <th className="py-2 font-medium">
                    <span className="sr-only">동작</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {status.product.skus.map((sku) => {
                  const stock = status.stocks.get(sku.skuId);
                  const label = skuLabel(status.product, sku.optionValueIds);
                  const selected = selection?.skuId === sku.skuId;
                  return (
                    <tr key={sku.skuId} className={cx('odd:bg-muted/50', selected && 'bg-muted')}>
                      <td className="py-2 pr-4">{label}</td>
                      <td className="py-2 pr-4 whitespace-nowrap">{formatMoney(sku.salePrice)}</td>
                      {stock ? (
                        <>
                          <td className="py-2 pr-4">{stock.stockManaged ? '관리' : '무제한'}</td>
                          <td className="py-2 pr-4 text-right tabular-nums">
                            {stock.stockManaged ? stock.availableQuantity : '—'}
                          </td>
                          <td className="py-2 pr-4 text-right tabular-nums">
                            {stock.reservedQuantity}
                          </td>
                          <td className="py-2 pr-4 whitespace-nowrap text-muted-fg">
                            {stock.updatedAt
                              ? dateTime.format(new Date(stock.updatedAt))
                              : '등록 전'}
                          </td>
                          <td className="py-2 whitespace-nowrap">
                            <div className="flex gap-2">
                              <Button
                                variant="secondary"
                                className="h-8 px-3"
                                aria-label={`${label} 재고 조정`}
                                onClick={() => {
                                  setNotice(null);
                                  setSelection({ skuId: sku.skuId, mode: 'adjust' });
                                }}
                              >
                                조정
                              </Button>
                              <Button
                                variant="secondary"
                                className="h-8 px-3"
                                aria-label={`${label} 조정 이력`}
                                onClick={() => {
                                  setNotice(null);
                                  setSelection({ skuId: sku.skuId, mode: 'history' });
                                }}
                              >
                                이력
                              </Button>
                            </div>
                          </td>
                        </>
                      ) : (
                        <td colSpan={5} className="py-2 text-muted-fg">
                          재고 정보를 받지 못했습니다.
                        </td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* live region 은 늘 두고 내용만 바꾼다 — 처음 나타날 때도 읽히게 */}
          <p role="status" className="text-sm empty:hidden">
            {notice && notice.skuId === selection?.skuId ? notice.message : ''}
          </p>

          {selection &&
            (() => {
              const sku = status.product.skus.find((item) => item.skuId === selection.skuId);
              const stock = status.stocks.get(selection.skuId);
              if (!sku || !stock) return null;
              const label = skuLabel(status.product, sku.optionValueIds);
              const version = `${stock.skuId}-${stock.updatedAt ?? 'new'}-${stock.availableQuantity}-${stock.stockManaged}`;
              return selection.mode === 'adjust' ? (
                <AdjustPanel
                  key={version}
                  label={label}
                  stock={stock}
                  onAdjusted={(next) => {
                    replaceStock(next);
                    setNotice({ skuId: next.skuId, message: '조정했습니다.' });
                  }}
                  onStale={(message) => void refreshSku(stock.skuId, message)}
                  onDenied={denied}
                  onSubmit={() => setNotice(null)}
                />
              ) : (
                <AdjustmentHistory
                  key={version}
                  skuId={stock.skuId}
                  label={label}
                  onDenied={denied}
                />
              );
            })()}
        </>
      )}
    </div>
  );
}
