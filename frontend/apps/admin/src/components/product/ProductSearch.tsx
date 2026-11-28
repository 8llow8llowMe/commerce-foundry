'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button, TextField } from '@cf/ui';
import { useEffect, useState, type FormEvent } from 'react';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import { ADMIN_PRODUCTS_PATH, type ProductSummary } from '@/lib/stock/types';

const PAGE_SIZE = 20;
const KEYWORD_MAX = 100;

export type ProductQuery = { keyword: string; page: number };

export type ProductSearchResult =
  | { query: ProductQuery; kind: 'ok'; data: PageResponse<ProductSummary> }
  | { query: ProductQuery; kind: 'failed' }
  | { query: ProductQuery; kind: 'denied'; denied: AccessDenied };

function productsPath({ keyword, page }: ProductQuery): string {
  const params = new URLSearchParams({
    sortType: 'NEWEST',
    page: String(page),
    size: String(PAGE_SIZE),
  });
  if (keyword) params.set('keyword', keyword);
  return `${ADMIN_PRODUCTS_PATH}?${params.toString()}`;
}

/**
 * 어드민 상품 목록 검색(#9, 상품명 검색 · 숨김 포함 · 최신순 · 페이지). 상품 하나 고르기(`ProductPicker`)와
 * 여러 개 고르기(사이즈표 일괄 초안)가 같이 쓴다. `current` 가 null 이면 지금 조건을 읽는 중이다.
 */
export function useProductSearch() {
  const [query, setQuery] = useState<ProductQuery>({ keyword: '', page: 0 });
  const [keyword, setKeyword] = useState('');
  const [loaded, setLoaded] = useState<ProductSearchResult | null>(null);
  const [retryCount, setRetryCount] = useState(0);

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
  }, [query, retryCount]);

  return {
    query,
    keyword,
    setKeyword,
    current: loaded?.query === query ? loaded : null,
    search: (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      setQuery({ keyword: keyword.trim(), page: 0 });
    },
    goToPage: (page: number) => setQuery({ ...query, page }),
    retry: () => setRetryCount((n) => n + 1),
  };
}

export type ProductSearch = ReturnType<typeof useProductSearch>;

/** 상품명 검색 칸 */
export function ProductSearchForm({ search, tour }: { search: ProductSearch; tour?: string }) {
  return (
    <form role="search" data-tour={tour} onSubmit={search.search} className="flex items-end gap-2">
      <TextField
        label="상품명 검색"
        type="search"
        maxLength={KEYWORD_MAX}
        value={search.keyword}
        onChange={(event) => search.setKeyword(event.currentTarget.value)}
        className="min-w-0 flex-1"
      />
      <Button type="submit" variant="secondary">
        검색
      </Button>
    </form>
  );
}

/** 이전 · 다음 페이지. 한 페이지뿐이면 그리지 않는다 */
export function ProductPager({
  search,
  totalPages,
}: {
  search: ProductSearch;
  totalPages: number;
}) {
  if (totalPages <= 1) return null;
  const page = search.query.page;
  return (
    <div className="flex items-center gap-3 text-sm">
      <Button variant="secondary" disabled={page === 0} onClick={() => search.goToPage(page - 1)}>
        이전
      </Button>
      <span>
        {page + 1} / {totalPages}
      </span>
      <Button
        variant="secondary"
        disabled={page + 1 >= totalPages}
        onClick={() => search.goToPage(page + 1)}
      >
        다음
      </Button>
    </div>
  );
}

/** 빈 결과 문구 */
export const emptyProductsText = (keyword: string) =>
  keyword ? `‘${keyword}’ 에 맞는 상품이 없습니다.` : '상품이 없습니다.';
