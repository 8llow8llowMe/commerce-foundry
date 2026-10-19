'use client';

import { ApiError, apiFetch, type SliceResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocale } from '@/components/i18n/LocaleProvider';
import { productsApiPath, type ShopQuery } from '@/lib/catalog/shop-query';
import type { ProductListItem } from '@/lib/catalog/types';
import { ProductCard } from './ProductCard';

export type ProductGridProps = {
  query: ShopQuery;
  /** 서버가 그린 0 페이지 */
  initial: SliceResponse<ProductListItem>;
};

/** 페이지 사이에 새 상품이 들어오면 offset 이 밀려 같은 상품이 다시 온다. id 로 한 번만 둔다 */
function appendUnique(prev: ProductListItem[], next: ProductListItem[]): ProductListItem[] {
  const seen = new Set(prev.map((item) => item.productId));
  return [...prev, ...next.filter((item) => !seen.has(item.productId))];
}

/**
 * 상품 목록 + 무한 스크롤. 끝 근처에 오면 다음 페이지를 부르고, '더 보기' 버튼도 같은 일을 한다
 * (키보드 · 스크린리더 · IntersectionObserver 미지원). 쿼리가 바뀌면 부모가 key 로 새로 그린다.
 */
export function ProductGrid({ query, initial }: ProductGridProps) {
  const [items, setItems] = useState(initial.contents);
  const [hasNext, setHasNext] = useState(initial.hasNext);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const loadingRef = useRef(false);
  const controllerRef = useRef<AbortController | null>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);

  const locale = useLocale();
  const loadMore = useCallback(async () => {
    if (loadingRef.current || !hasNext) return;
    loadingRef.current = true;
    setLoading(true);
    setError(null);
    const controller = new AbortController();
    controllerRef.current = controller;
    try {
      const next = await apiFetch<SliceResponse<ProductListItem>>(
        productsApiPath(query, page + 1),
        // BFF 는 Accept-Language 를 게이트웨이로 넘긴다 — 브라우저 언어가 아니라 스토어 언어로 받는다
        { signal: controller.signal, headers: { 'accept-language': locale } },
      );
      setItems((prev) => appendUnique(prev, next.contents));
      setHasNext(next.hasNext);
      setPage((current) => current + 1);
    } catch (cause) {
      // 화면을 떠나 취소된 요청은 안내하지 않는다
      if (controller.signal.aborted) return;
      setError(cause instanceof ApiError ? cause.message : '상품을 더 불러오지 못했습니다.');
    } finally {
      loadingRef.current = false;
      if (!controller.signal.aborted) setLoading(false);
    }
  }, [hasNext, locale, page, query]);

  useEffect(() => () => controllerRef.current?.abort(), []);

  // 오류가 나면 자동으로 다시 부르지 않는다 — 사용자가 '다시 시도' 를 누른다
  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel || !hasNext || error) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) void loadMore();
      },
      { rootMargin: '800px 0px' },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [error, hasNext, loadMore]);

  if (items.length === 0) {
    return <p className="py-24 text-center text-sm text-muted-fg">아직 등록된 상품이 없습니다.</p>;
  }

  return (
    <div>
      <ul className="grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 lg:grid-cols-4 lg:gap-x-6 lg:gap-y-14">
        {items.map((product) => (
          <li key={product.productId}>
            <ProductCard product={product} />
          </li>
        ))}
      </ul>

      <div ref={sentinelRef} className="mt-14 flex flex-col items-center gap-3">
        <p role="status" className="text-sm text-muted-fg">
          {loading ? '상품을 불러오는 중입니다.' : (error ?? '')}
        </p>
        {hasNext && !loading && (
          <Button variant="secondary" onClick={() => void loadMore()}>
            {error ? '다시 시도' : '더 보기'}
          </Button>
        )}
      </div>
    </div>
  );
}
