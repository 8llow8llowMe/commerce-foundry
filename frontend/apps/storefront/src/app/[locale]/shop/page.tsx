import { ApiError, type SliceResponse } from '@cf/api-client';
import { cx } from '@cf/ui';
import type { Metadata } from 'next';
import { ProductGrid } from '@/components/product/ProductGrid';
import { ShopFilters } from '@/components/product/ShopFilters';
import { categoryTrail, parseShopQuery, productsApiPath, shopHref } from '@/lib/catalog/shop-query';
import type { CategoryTreeResponse, ProductListItem } from '@/lib/catalog/types';
import { getI18n, localeAlternates } from '@/lib/i18n/server';
import { serverFetch } from '@/lib/server-api';

export async function generateMetadata(): Promise<Metadata> {
  // 카테고리 · 정렬은 같은 목록의 보기라 정식 주소는 /shop 하나로 둔다
  return { title: 'Shop', alternates: await localeAlternates('/shop') };
}

export default async function ShopPage({ searchParams }: PageProps<'/[locale]/shop'>) {
  const query = parseShopQuery(await searchParams);
  const { href, dict } = await getI18n();

  // 카테고리를 못 받아도 상품 목록은 보여 준다(필터 줄만 빠진다)
  const [categoriesResult, productsResult] = await Promise.allSettled([
    serverFetch<CategoryTreeResponse>('/api/v1/categories'),
    serverFetch<SliceResponse<ProductListItem>>(productsApiPath(query, 0)),
  ]);
  const categories =
    categoriesResult.status === 'fulfilled' ? categoriesResult.value.categories : [];
  const trail = categoryTrail(categories, query.categoryId);
  const current = trail.at(-1) ?? null;

  return (
    <main className="mx-auto max-w-screen-xl px-4 pt-10 pb-24 lg:px-8 lg:pt-14">
      {/* 카테고리 이름은 운영 데이터(한글일 수 있다)라 한글 글꼴로, 기본 제목은 영문 제목 글꼴로 */}
      <h1
        className={cx(
          'mb-8 text-center text-3xl lg:mb-12 lg:text-4xl',
          current ? 'font-serif' : 'font-display tracking-[0.15em] uppercase',
        )}
      >
        {current?.name ?? 'Shop'}
      </h1>

      <ShopFilters categories={categories} query={query} labels={dict.shop} />

      <div className="mt-8 lg:mt-10">
        {productsResult.status === 'fulfilled' ? (
          // 쿼리가 바뀌면 불러온 페이지 · 스크롤 상태를 버리고 새로 시작한다
          <ProductGrid key={shopHref(query)} query={query} initial={productsResult.value} />
        ) : (
          <div className="flex flex-col items-center gap-4 py-24 text-center">
            <p className="text-sm text-muted-fg">
              {/* 서버 → 게이트웨이 연결 실패(network)는 사용자 네트워크 문제가 아니다 — 일반 문구로 */}
              {productsResult.reason instanceof ApiError && productsResult.reason.kind !== 'network'
                ? productsResult.reason.message
                : dict.shop.loadFailed}
            </p>
            {/* 같은 URL 이라 클라이언트 라우팅으로는 다시 받지 않는다 — 문서를 새로 받는다 */}
            <a href={href(shopHref(query))} className="text-sm underline underline-offset-4">
              {dict.shop.reload}
            </a>
          </div>
        )}
      </div>
    </main>
  );
}
