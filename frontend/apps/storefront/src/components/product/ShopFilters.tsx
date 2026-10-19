import { cx } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import {
  SORT_LABELS,
  SORT_TYPES,
  categoryTrail,
  shopHref,
  type ShopQuery,
} from '@/lib/catalog/shop-query';
import type { CategoryItem } from '@/lib/catalog/types';

const ITEM = 'text-xs tracking-[0.15em] uppercase whitespace-nowrap hover:text-fg';

/**
 * 카테고리(최상위 + 고른 최상위의 하위)와 정렬. 전부 링크라 JS 없이도 동작하고 URL 로 공유된다.
 * 정렬을 바꿔도 카테고리는, 카테고리를 바꿔도 정렬은 유지한다.
 */
export function ShopFilters({
  categories,
  query,
}: {
  categories: CategoryItem[];
  query: ShopQuery;
}) {
  const trail = categoryTrail(categories, query.categoryId);
  const top = trail[0] ?? null;

  const link = (categoryId: string | null, label: string, active: boolean) => (
    <Link
      href={shopHref({ ...query, categoryId })}
      aria-current={active ? 'page' : undefined}
      className={cx(ITEM, active ? 'text-fg underline underline-offset-8' : 'text-muted-fg')}
    >
      {label}
    </Link>
  );

  return (
    <div className="flex flex-col gap-4 border-b border-border pb-4 lg:flex-row lg:items-end lg:justify-between">
      {categories.length > 0 && (
        <nav aria-label="카테고리" className="flex flex-col gap-3">
          {/* 좁은 화면에서는 이 줄만 가로로 넘긴다(페이지 전체는 가로 스크롤 없음) */}
          <ul className="-mx-4 flex gap-6 overflow-x-auto px-4 lg:mx-0 lg:px-0">
            <li>{link(null, '전체', top === null)}</li>
            {categories.map((category) => (
              <li key={category.categoryId}>
                {link(category.categoryId, category.name, top?.categoryId === category.categoryId)}
              </li>
            ))}
          </ul>
          {top && top.children.length > 0 && (
            <ul className="-mx-4 flex gap-5 overflow-x-auto px-4 lg:mx-0 lg:px-0">
              <li>{link(top.categoryId, `${top.name} 전체`, trail.length === 1)}</li>
              {top.children.map((child) => (
                <li key={child.categoryId}>
                  {link(child.categoryId, child.name, trail[1]?.categoryId === child.categoryId)}
                </li>
              ))}
            </ul>
          )}
        </nav>
      )}

      <nav aria-label="정렬" className="lg:ml-auto">
        <ul className="flex gap-5">
          {SORT_TYPES.map((sort) => (
            <li key={sort}>
              <Link
                href={shopHref({ ...query, sort })}
                aria-current={query.sort === sort ? 'true' : undefined}
                className={cx(ITEM, query.sort === sort ? 'text-fg' : 'text-muted-fg')}
              >
                {SORT_LABELS[sort]}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </div>
  );
}
