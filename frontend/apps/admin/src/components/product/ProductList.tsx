'use client';

import { apiFetch, formatMoney, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { flattenTree } from '@/lib/category/tree';
import { CATEGORIES_PATH, type CategoryTree } from '@/lib/category/types';
import {
  hasFilter,
  productsApiPath,
  productsHref,
  type ProductListItem,
  type ProductQuery,
} from '@/lib/product/query';
import { ProductFilters, type Categories } from './ProductFilters';

type Loaded =
  | { path: string; kind: 'ok'; data: PageResponse<ProductListItem> }
  | { path: string; kind: 'failed' }
  | { path: string; kind: 'denied'; denied: AccessDenied };

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/**
 * 어드민 상품 목록(#208) — 숨김 포함, 조건은 주소(`query`)가 정본이다. 등록 · 수정은 BE #9 PR-D/E 뒤에 붙인다.
 * 가격은 저장된 기본 판매가 · 정가를 표시만 한다(`formatMoney`). 판매 가능은 재고를 반영한 서버 값이다.
 */
export function ProductList({ query }: { query: ProductQuery }) {
  const path = productsApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [retry, setRetry] = useState(0);
  const [categories, setCategories] = useState<Categories>(null);

  // 카테고리 트리는 화면에 한 번만 읽는다 — 조건 폼은 주소마다 새로 시작하지만(key) 트리까지 다시 받을 까닭은 없다
  useEffect(() => {
    let alive = true;
    apiFetch<CategoryTree>(CATEGORIES_PATH).then(
      (data) => alive && setCategories({ kind: 'ok', rows: flattenTree(data.categories) }),
      () => alive && setCategories({ kind: 'failed' }),
    );
    return () => {
      alive = false;
    };
  }, []);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 `loaded.path !== path` 로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<ProductListItem>>(path).then(
      (data) => alive && setLoaded({ path, kind: 'ok', data }),
      (cause: unknown) => {
        if (!alive) return;
        const denied = accessDeniedOf(cause);
        setLoaded(denied ? { path, kind: 'denied', denied } : { path, kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [path, retry]);

  const current = loaded?.path === path ? loaded : null;
  const href = productsHref(query);

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">상품</h1>
        <p className="mt-1 text-sm text-muted-fg">
          숨긴 상품 · 판매 종료 상품까지 모두 보입니다. 상품 등록 · 수정은 준비 중입니다.
        </p>
      </div>

      <ProductFilters key={href} query={query} categories={categories} />

      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'denied' && <AccessNotice denied={current.denied} />}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => setRetry((n) => n + 1)}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' &&
        current.data.contents.length === 0 &&
        (current.data.totalElements > 0 ? (
          // 손으로 고친 주소 · 그사이 줄어든 목록 — 상품은 있으니 "없다" 고 하지 않고 돌아갈 길을 준다
          <p className="text-sm text-muted-fg">
            이 페이지에는 상품이 없습니다.{' '}
            <Link
              href={productsHref({ ...query, page: 0 })}
              className="text-fg underline underline-offset-4"
            >
              첫 페이지로
            </Link>
          </p>
        ) : (
          <p className="text-sm text-muted-fg">
            {hasFilter(query) ? '조건에 맞는 상품이 없습니다.' : '아직 상품이 없습니다.'}
          </p>
        ))}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">{current.data.totalElements}개</p>
          {/* sr-only 같은 absolute 요소가 스크롤 상자 밖으로 나가지 않게 relative 를 준다(§9 재고) */}
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[56rem] text-left text-sm">
              <caption className="sr-only">상품 목록</caption>
              <thead className="text-muted-fg">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    상품명
                  </th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">
                    판매가
                  </th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">
                    정가
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    판매 상태
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    노출
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    판매 가능
                  </th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">
                    SKU
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    수정일
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    <span className="sr-only">동작</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {current.data.contents.map((product) => (
                  <tr key={product.productId} className="border-t border-border">
                    <th scope="row" className="max-w-[18rem] py-3 pr-4 font-medium">
                      <span className="line-clamp-2">{product.name}</span>
                    </th>
                    <td className="py-3 pr-4 text-right whitespace-nowrap">
                      {formatMoney(product.salePrice)}
                    </td>
                    <td className="py-3 pr-4 text-right whitespace-nowrap text-muted-fg">
                      {product.listPrice ? formatMoney(product.listPrice) : '—'}
                    </td>
                    <td
                      className="py-3 pr-4 whitespace-nowrap"
                      title={product.saleStatus.description}
                    >
                      {product.saleStatus.name}
                    </td>
                    <td className="py-3 pr-4 whitespace-nowrap">
                      {product.visible ? '노출' : <span className="text-muted-fg">숨김</span>}
                    </td>
                    <td
                      className="py-3 pr-4 whitespace-nowrap"
                      title={product.availability.description}
                    >
                      {product.availability.name}
                    </td>
                    <td className="py-3 pr-4 text-right">{product.skuCount}</td>
                    <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                      {dateTime.format(new Date(product.updatedAt))}
                    </td>
                    <td className="py-3 whitespace-nowrap">
                      <div className="flex gap-3">
                        {/* 숨긴 상품은 옵션 · SKU 를 읽을 어드민 상세 API 가 없어 재고 화면을 열 수 없다(§9 재고) */}
                        {product.visible && (
                          <Link
                            href={`/stocks/${product.productId}`}
                            className="underline-offset-4 hover:underline"
                            aria-label={`${product.name} 재고`}
                          >
                            재고
                          </Link>
                        )}
                        {/* 원가(#395)도 SKU 를 고객 상세에서 읽어 재고와 같은 이유로 노출 상품만이다 */}
                        {product.visible && (
                          <Link
                            href={`/sku-costs/${product.productId}`}
                            className="underline-offset-4 hover:underline"
                            aria-label={`${product.name} 원가`}
                          >
                            원가
                          </Link>
                        )}
                        <Link
                          href={`/size-charts/${product.productId}`}
                          className="underline-offset-4 hover:underline"
                          aria-label={`${product.name} 사이즈표`}
                        >
                          사이즈표
                        </Link>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {current.data.totalPages > 1 && (
            <nav aria-label="페이지" className="flex items-center gap-3 text-sm">
              <PageLink query={query} page={query.page - 1} disabled={query.page === 0}>
                이전
              </PageLink>
              <span>
                {query.page + 1} / {current.data.totalPages}
              </span>
              <PageLink
                query={query}
                page={query.page + 1}
                disabled={query.page + 1 >= current.data.totalPages}
              >
                다음
              </PageLink>
            </nav>
          )}
        </>
      )}
    </div>
  );
}

function PageLink({
  query,
  page,
  disabled,
  children,
}: {
  query: ProductQuery;
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={productsHref({ ...query, page })}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
