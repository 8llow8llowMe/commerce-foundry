'use client';

import { ApiError, apiFetch, formatMoney } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { skuLabel } from '@/lib/stock/adjustment';
import type { ProductForStock } from '@/lib/stock/types';
import { SkuCostPanel } from './SkuCostPanel';

type Status =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'loadFailed' }
  | { kind: 'ready'; product: ProductForStock };

/**
 * 상품 하나의 SKU 원가(#395). 옵션 · SKU 는 재고(#188)와 같이 고객 상품 상세에서 읽는다(어드민 상품 상세 API 가 아직 없다).
 * 원가는 SKU 하나씩 패널에서 연다 — 현재 원가 API 가 SKU 단건이라 표에 다 펼치면 SKU 수만큼 부른다.
 */
export function ProductSkuCosts({ productId }: { productId: string }) {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [selected, setSelected] = useState<string | null>(null);

  const denied = useCallback(
    (kind: AccessDenied) => setStatus({ kind: 'denied', denied: kind }),
    [],
  );

  // 상태는 응답 콜백에서만 바꾼다 — 마운트 effect 가 부른다
  const load = useCallback(
    () =>
      apiFetch<ProductForStock>(`/api/v1/products/${encodeURIComponent(productId)}`).then(
        (product) => setStatus({ kind: 'ready', product }),
        (cause: unknown) => {
          const kind = accessDeniedOf(cause);
          if (kind) setStatus({ kind: 'denied', denied: kind });
          else if (cause instanceof ApiError && cause.kind === 'notFound')
            setStatus({ kind: 'notFound' });
          else setStatus({ kind: 'loadFailed' });
        },
      ),
    [productId],
  );

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href="/sku-costs" className="text-sm text-muted-fg underline underline-offset-4">
          상품 목록
        </Link>
        <h1 className="text-xl font-semibold">
          {status.kind === 'ready' ? `${status.product.name} 원가` : '원가'}
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
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[28rem] text-left text-sm">
              <thead className="text-muted-fg">
                <tr>
                  <th className="py-2 pr-4 font-medium">옵션</th>
                  <th className="py-2 pr-4 font-medium">판매가</th>
                  <th className="py-2 font-medium">
                    <span className="sr-only">동작</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {status.product.skus.map((sku) => {
                  const label = skuLabel(status.product, sku.optionValueIds);
                  return (
                    <tr
                      key={sku.skuId}
                      className={cx('odd:bg-muted/50', selected === sku.skuId && 'bg-muted')}
                    >
                      <td className="py-2 pr-4">{label}</td>
                      <td className="py-2 pr-4 whitespace-nowrap">{formatMoney(sku.salePrice)}</td>
                      <td className="py-2 whitespace-nowrap">
                        <Button
                          variant="secondary"
                          className="h-8 px-3"
                          aria-label={`${label} 원가`}
                          aria-pressed={selected === sku.skuId}
                          onClick={() => setSelected(sku.skuId)}
                        >
                          원가
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {selected &&
            (() => {
              const sku = status.product.skus.find((item) => item.skuId === selected);
              if (!sku) return null;
              return (
                <SkuCostPanel
                  key={sku.skuId}
                  skuId={sku.skuId}
                  label={skuLabel(status.product, sku.optionValueIds)}
                  onDenied={denied}
                />
              );
            })()}
        </>
      )}
    </div>
  );
}
