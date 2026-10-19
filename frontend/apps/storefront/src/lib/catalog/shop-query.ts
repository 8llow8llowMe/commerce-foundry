import type { CategoryItem } from './types';

/** BE `ProductSortType`. 같은 값이면 상품 id 내림차순으로 끊는다 */
export const SORT_TYPES = ['NEWEST', 'PRICE_ASC', 'PRICE_DESC'] as const;
export type ProductSortType = (typeof SORT_TYPES)[number];

export const SORT_LABELS: Record<ProductSortType, string> = {
  NEWEST: '신상품순',
  PRICE_ASC: '낮은 가격순',
  PRICE_DESC: '높은 가격순',
};

/** 한 번에 불러오는 상품 수. BE 상한은 100 이다. 2 · 3 · 4 열 모두 나누어떨어지게 둔다 */
export const PAGE_SIZE = 24;

/** /shop 화면의 상태. URL 쿼리(`?category=&sort=`)가 정본이다 */
export type ShopQuery = {
  categoryId: string | null;
  sort: ProductSortType;
};

type RawSearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** 잘못된 값은 오류 대신 기본값으로 둔다 — 사람이 고친 URL 로 400 화면을 보이지 않는다 */
export function parseShopQuery(raw: RawSearchParams): ShopQuery {
  const category = first(raw.category);
  const sort = first(raw.sort);
  return {
    categoryId: category && /^\d{1,19}$/.test(category) ? category : null,
    sort: SORT_TYPES.find((type) => type === sort) ?? 'NEWEST',
  };
}

/** 화면 URL. 기본값은 쿼리에서 뺀다 */
export function shopHref(query: ShopQuery): string {
  const params = new URLSearchParams();
  if (query.categoryId) params.set('category', query.categoryId);
  if (query.sort !== 'NEWEST') params.set('sort', query.sort);
  const search = params.toString();
  return search ? `/shop?${search}` : '/shop';
}

/** 상품 목록 API 경로. page 는 0 부터, size 는 1~100(BE 검증) */
export function productsApiPath(query: ShopQuery, page: number, size: number = PAGE_SIZE): string {
  const params = new URLSearchParams();
  if (query.categoryId) params.set('categoryId', query.categoryId);
  params.set('sortType', query.sort);
  params.set('page', String(page));
  params.set('size', String(size));
  return `/api/v1/products?${params.toString()}`;
}

/** 최상위부터 해당 카테고리까지의 경로. 없으면 빈 배열 */
export function categoryTrail(tree: CategoryItem[], categoryId: string | null): CategoryItem[] {
  if (!categoryId) return [];
  for (const node of tree) {
    if (node.categoryId === categoryId) return [node];
    const rest = categoryTrail(node.children, categoryId);
    if (rest.length > 0) return [node, ...rest];
  }
  return [];
}
