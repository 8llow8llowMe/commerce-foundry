import { cx } from '@cf/ui';
import { CloseIcon } from '@/components/header/HeaderIcons';
import { Link } from '@/components/i18n/Link';
import {
  SORT_TYPES,
  categoryTrail,
  shopHref,
  type ShopQuery,
  type ProductSortType,
} from '@/lib/catalog/shop-query';
import type { CategoryItem } from '@/lib/catalog/types';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';

const ITEM = 'text-xs tracking-[0.15em] uppercase whitespace-nowrap hover:text-fg';
const SHEET_ID = 'shop-sort-filter';

/**
 * 카테고리(최상위 + 고른 최상위의 하위)와 정렬. 전부 링크라 JS 없이도 동작하고 URL 로 공유된다.
 * 정렬을 바꿔도 카테고리는, 카테고리를 바꿔도 정렬은 유지한다.
 *
 * 모바일(#60)은 최상위 카테고리 줄만 화면에 두고, 정렬 · 세부 카테고리는 하단 고정 버튼 `정렬 · 필터` 로 여는
 * 바텀시트에 둔다. 시트는 Popover API(`popover` 속성)라 스크립트 없이 열리고, 바깥을 누르거나 ESC 로 닫힌다.
 * 링크로 주소가 바뀌면 key 가 바뀌어 시트가 닫힌 채로 다시 그려진다.
 */
export function ShopFilters({
  categories,
  query,
  labels,
}: {
  categories: CategoryItem[];
  query: ShopQuery;
  labels: Dictionary['shop'];
}) {
  const trail = categoryTrail(categories, query.categoryId);
  const top = trail[0] ?? null;
  const children = top?.children ?? [];

  const link = (categoryId: string | null, label: string, active: boolean) => (
    <Link
      href={shopHref({ ...query, categoryId })}
      aria-current={active ? 'page' : undefined}
      className={cx(ITEM, active ? 'text-fg underline underline-offset-8' : 'text-muted-fg')}
    >
      {label}
    </Link>
  );
  const sortLink = (sort: ProductSortType) => (
    <Link
      href={shopHref({ ...query, sort })}
      aria-current={query.sort === sort ? 'true' : undefined}
      className={cx(ITEM, query.sort === sort ? 'text-fg' : 'text-muted-fg')}
    >
      {labels.sortTypes[sort]}
    </Link>
  );
  const childLinks = top
    ? [
        {
          id: top.categoryId,
          label: fill(labels.allOf, { name: top.name }),
          active: trail.length === 1,
        },
        ...children.map((child) => ({
          id: child.categoryId,
          label: child.name,
          active: trail[1]?.categoryId === child.categoryId,
        })),
      ]
    : [];

  return (
    <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      {categories.length > 0 && (
        <nav aria-label={labels.categories} className="flex flex-col gap-3">
          {/* 좁은 화면에서는 이 줄만 가로로 넘긴다(페이지 전체는 가로 스크롤 없음) */}
          <ul className="relative -mx-4 flex gap-6 overflow-x-auto px-4 lg:mx-0 lg:px-0">
            <li>{link(null, labels.all, top === null)}</li>
            {categories.map((category) => (
              <li key={category.categoryId}>
                {link(category.categoryId, category.name, top?.categoryId === category.categoryId)}
              </li>
            ))}
          </ul>
          {children.length > 0 && (
            <ul className="hidden gap-5 lg:flex">
              {childLinks.map((item) => (
                <li key={item.id}>{link(item.id, item.label, item.active)}</li>
              ))}
            </ul>
          )}
        </nav>
      )}

      <nav aria-label={labels.sort} className="hidden lg:ml-auto lg:block">
        <ul className="flex gap-5">
          {SORT_TYPES.map((sort) => (
            <li key={sort}>{sortLink(sort)}</li>
          ))}
        </ul>
      </nav>

      {/* 모바일 하단 고정 버튼 — 상품 목록 위에 떠 있다(구매 바와 같은 경계선) */}
      <div className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-bg px-4 py-3 lg:hidden">
        <button
          type="button"
          popoverTarget={SHEET_ID}
          className="h-12 w-full bg-primary text-sm tracking-[0.15em] text-primary-fg"
        >
          {labels.sortAndFilter}
        </button>
      </div>

      <div
        key={shopHref(query)}
        id={SHEET_ID}
        popover="auto"
        role="dialog"
        aria-labelledby={`${SHEET_ID}-title`}
        className="inset-x-0 top-auto bottom-0 m-0 w-full max-w-none bg-bg px-4 pt-4 pb-[calc(2rem+env(safe-area-inset-bottom))] text-fg backdrop:bg-fg/40 lg:hidden"
      >
        <div className="flex items-center justify-between">
          <p id={`${SHEET_ID}-title`} className="text-sm tracking-[0.15em]">
            {labels.sortAndFilter}
          </p>
          <button
            type="button"
            popoverTarget={SHEET_ID}
            popoverTargetAction="hide"
            aria-label={labels.closeSheet}
            className="-mr-2 inline-flex size-10 items-center justify-center"
          >
            <CloseIcon />
          </button>
        </div>

        <div className="mt-6 flex flex-col gap-8">
          <nav aria-label={labels.sort}>
            <ul className="flex flex-col gap-5">
              {SORT_TYPES.map((sort) => (
                <li key={sort}>{sortLink(sort)}</li>
              ))}
            </ul>
          </nav>
          {children.length > 0 && (
            <nav aria-label={labels.categories}>
              <ul className="flex flex-col gap-5">
                {childLinks.map((item) => (
                  <li key={item.id}>{link(item.id, item.label, item.active)}</li>
                ))}
              </ul>
            </nav>
          )}
        </div>
      </div>
    </div>
  );
}
