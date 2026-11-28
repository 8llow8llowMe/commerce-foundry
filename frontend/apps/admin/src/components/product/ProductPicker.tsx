'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import type { ReactNode } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED } from '@/lib/api-errors';
import {
  emptyProductsText,
  ProductPager,
  ProductSearchForm,
  useProductSearch,
} from './ProductSearch';

/**
 * 상품 하나를 고르는 목록 — 어드민 상품 목록(#9, 상품명 검색 · 숨김 포함 · 페이지).
 * 재고(#188) · 사이즈표(#218) · 원가(#395)가 쓴다. 고르면 `{basePath}/{productId}` 로 간다.
 * `hiddenNote` 를 주면 숨긴 상품은 고를 수 없다고 흐리게 보이고 그 이유를 적는다.
 * 검색 · 페이지는 `useProductSearch` 다(여러 개 고르기와 같이 쓴다).
 */
export function ProductPicker({
  title,
  description,
  basePath,
  hiddenNote,
  actions,
}: {
  title: string;
  description: string;
  /** 고른 뒤 갈 화면의 앞부분(`/stocks`) */
  basePath: string;
  /** 숨긴 상품을 고를 수 없는 이유(줄 title · 목록 아래 안내). 없으면 숨긴 상품도 고른다 */
  hiddenNote?: string;
  /** 설명 아래에 둘 다른 화면 링크 등 */
  actions?: ReactNode;
}) {
  const search = useProductSearch();
  const { current, query } = search;

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">{title}</h1>
        <p className="mt-1 text-sm text-muted-fg">{description}</p>
        {actions && <div className="mt-3">{actions}</div>}
      </div>

      <ProductSearchForm search={search} tour="product-search" />

      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'denied' && <AccessNotice denied={current.denied} />}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={search.retry}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' && current.data.contents.length === 0 && (
        <p className="text-sm text-muted-fg">{emptyProductsText(query.keyword)}</p>
      )}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">{current.data.totalElements}개</p>
          <ul data-tour="product-list" className="flex flex-col gap-1">
            {current.data.contents.map((product) => {
              const href =
                hiddenNote && !product.visible ? null : `${basePath}/${product.productId}`;
              const meta = (
                <span className="shrink-0 text-muted-fg">
                  {product.visible ? product.availability.name : '숨김'} · SKU {product.skuCount}
                </span>
              );
              return (
                <li key={product.productId}>
                  {href ? (
                    <Link
                      href={href}
                      className="flex items-center justify-between gap-4 rounded-md bg-muted/50 px-4 py-3 text-sm hover:bg-muted"
                    >
                      <span className="min-w-0 truncate font-medium">{product.name}</span>
                      {meta}
                    </Link>
                  ) : (
                    <div
                      className="flex items-center justify-between gap-4 rounded-md px-4 py-3 text-sm text-muted-fg"
                      title={hiddenNote}
                    >
                      <span className="min-w-0 truncate">{product.name}</span>
                      {meta}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
          {hiddenNote && <p className="text-xs text-muted-fg">{hiddenNote}</p>}
          <ProductPager search={search} totalPages={current.data.totalPages} />
        </>
      )}
    </div>
  );
}
