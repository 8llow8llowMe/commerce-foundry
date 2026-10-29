'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button, TextField } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState, type FormEvent } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { ADMIN_PRODUCTS_PATH, type ProductSummary } from '@/lib/stock/types';

const PAGE_SIZE = 20;
const KEYWORD_MAX = 100;

type Query = { keyword: string; page: number };

type Loaded =
  | { query: Query; kind: 'ok'; data: PageResponse<ProductSummary> }
  | { query: Query; kind: 'failed' }
  | { query: Query; kind: 'denied'; denied: AccessDenied };

function productsPath({ keyword, page }: Query): string {
  const params = new URLSearchParams({
    sortType: 'NEWEST',
    page: String(page),
    size: String(PAGE_SIZE),
  });
  if (keyword) params.set('keyword', keyword);
  return `${ADMIN_PRODUCTS_PATH}?${params.toString()}`;
}

/**
 * 상품 하나를 고르는 목록 — 어드민 상품 목록(#9, 상품명 검색 · 숨김 포함 · 페이지).
 * 재고(#188) · 사이즈표(#218) · 원가(#395)가 쓴다. 고르면 `{basePath}/{productId}` 로 간다.
 * `hiddenNote` 를 주면 숨긴 상품은 고를 수 없다고 흐리게 보이고 그 이유를 적는다.
 */
export function ProductPicker({
  title,
  description,
  basePath,
  hiddenNote,
}: {
  title: string;
  description: string;
  /** 고른 뒤 갈 화면의 앞부분(`/stocks`) */
  basePath: string;
  /** 숨긴 상품을 고를 수 없는 이유(줄 title · 목록 아래 안내). 없으면 숨긴 상품도 고른다 */
  hiddenNote?: string;
}) {
  const [query, setQuery] = useState<Query>({ keyword: '', page: 0 });
  const [draft, setDraft] = useState('');
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [retry, setRetry] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 `loaded.query !== query` 로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<ProductSummary>>(productsPath(query)).then(
      (data) => alive && setLoaded({ query, kind: 'ok', data }),
      (cause: unknown) => {
        if (!alive) return;
        const denied = accessDeniedOf(cause);
        setLoaded(denied ? { query, kind: 'denied', denied } : { query, kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [query, retry]);

  const current = loaded?.query === query ? loaded : null;

  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setQuery({ keyword: draft.trim(), page: 0 });
  };

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">{title}</h1>
        <p className="mt-1 text-sm text-muted-fg">{description}</p>
      </div>

      <form
        role="search"
        data-tour="product-search"
        onSubmit={search}
        className="flex items-end gap-2"
      >
        <TextField
          label="상품명 검색"
          type="search"
          maxLength={KEYWORD_MAX}
          value={draft}
          onChange={(event) => setDraft(event.currentTarget.value)}
          className="flex-1"
        />
        <Button type="submit" variant="secondary">
          검색
        </Button>
      </form>

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
      {current?.kind === 'ok' && current.data.contents.length === 0 && (
        <p className="text-sm text-muted-fg">
          {query.keyword ? `‘${query.keyword}’ 에 맞는 상품이 없습니다.` : '상품이 없습니다.'}
        </p>
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
          {current.data.totalPages > 1 && (
            <div className="flex items-center gap-3 text-sm">
              <Button
                variant="secondary"
                disabled={query.page === 0}
                onClick={() => setQuery({ ...query, page: query.page - 1 })}
              >
                이전
              </Button>
              <span>
                {query.page + 1} / {current.data.totalPages}
              </span>
              <Button
                variant="secondary"
                disabled={query.page + 1 >= current.data.totalPages}
                onClick={() => setQuery({ ...query, page: query.page + 1 })}
              >
                다음
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
