import type { Money } from '@cf/api-client';

/**
 * 어드민 상품 목록 `GET /api/v1/admin/products`(#9, product-service `ProductAdminController`).
 * 조건을 주지 않으면 숨김 · 판매 종료까지 전체다. categoryId 는 하위 카테고리를 포함한다(없는 카테고리면 빈 목록).
 */
export const ADMIN_PRODUCTS_PATH = '/api/v1/admin/products';
export const PAGE_SIZE = 20;
/** 서버 `@Size(max = 100)` */
export const KEYWORD_MAX = 100;

export const SALE_STATUSES = ['ON_SALE', 'STOP_SELLING'] as const;
export const AVAILABILITIES = ['ON_SALE', 'SOLD_OUT', 'STOP_SELLING'] as const;
export const SORT_TYPES = ['NEWEST', 'NAME_ASC'] as const;

export type SaleStatus = (typeof SALE_STATUSES)[number];
export type Availability = (typeof AVAILABILITIES)[number];
export type SortType = (typeof SORT_TYPES)[number];

/** 화면 조건. null 은 "전체"(서버에 보내지 않는다) */
export type ProductQuery = {
  keyword: string;
  categoryId: string | null;
  saleStatus: SaleStatus | null;
  visible: boolean | null;
  availability: Availability | null;
  sortType: SortType;
  page: number;
};

export const DEFAULT_QUERY: ProductQuery = {
  keyword: '',
  categoryId: null,
  saleStatus: null,
  visible: null,
  availability: null,
  sortType: 'NEWEST',
  page: 0,
};

/** `ProductListAdminItem` */
export type ProductListItem = {
  productId: string;
  name: string;
  thumbnailUrl: string | null;
  /** 저장된 기본 판매가 그대로(옵션 추가금 · 최저가 반영 없음) */
  salePrice: Money;
  listPrice: Money | null;
  /** 저장된 판매 상태 — 판매중 · 판매 종료 */
  saleStatus: { code: string; name: string; description: string };
  visible: boolean;
  /** 재고를 반영한 판매 가능 상태 — 고객 화면 뱃지와 같은 규칙(노출 여부는 보지 않는다) */
  availability: { code: string; name: string; description: string };
  skuCount: number;
  updatedAt: string;
};

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);
const oneOf = <T extends string>(values: readonly T[], value: string | undefined): T | null =>
  values.find((item) => item === value) ?? null;

/**
 * 주소(query string) → 조건. 허용 값만 받고 나머지는 "전체"(기본값)로 본다 — 손으로 고친 주소가 400 이 되지 않게.
 * 검색어는 앞뒤 공백을 빼고 100자에서 자른다. 카테고리는 Long 범위 양의 정수, 페이지는 숫자 글자만 받는다.
 */
export function parseProductQuery(params: SearchParams): ProductQuery {
  // 자른 자리에 공백이 오면 끝 공백이 남으므로 한 번 더 뺀다
  const keyword = (first(params.keyword) ?? '').trim().slice(0, KEYWORD_MAX).trim();
  const categoryId = first(params.categoryId);
  const visible = first(params.visible);
  const page = first(params.page);
  return {
    keyword,
    categoryId: categoryId && isLongId(categoryId) ? categoryId : null,
    saleStatus: oneOf(SALE_STATUSES, first(params.saleStatus)),
    visible: visible === 'true' ? true : visible === 'false' ? false : null,
    availability: oneOf(AVAILABILITIES, first(params.availability)),
    sortType: oneOf(SORT_TYPES, first(params.sortType)) ?? DEFAULT_QUERY.sortType,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/** 양의 정수이고 서버 Long 범위 안인지 — 넘으면 서버 바인딩이 400 이다 */
function isLongId(value: string): boolean {
  return /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9_223_372_036_854_775_807n;
}

/** 조건 → 주소 파라미터. 기본값은 빼서 주소를 짧게 둔다 */
function queryParams(query: ProductQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.categoryId) params.set('categoryId', query.categoryId);
  if (query.saleStatus) params.set('saleStatus', query.saleStatus);
  if (query.visible !== null) params.set('visible', String(query.visible));
  if (query.availability) params.set('availability', query.availability);
  if (query.sortType !== DEFAULT_QUERY.sortType) params.set('sortType', query.sortType);
  if (query.page > 0) params.set('page', String(query.page));
  return params;
}

/** 화면 주소 */
export function productsHref(query: ProductQuery): string {
  const search = queryParams(query).toString();
  return search ? `/products?${search}` : '/products';
}

/** BFF 경로. 정렬 · 페이지 · 크기는 늘 보낸다 */
export function productsApiPath(query: ProductQuery): string {
  const params = queryParams(query);
  params.set('sortType', query.sortType);
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_PRODUCTS_PATH}?${params.toString()}`;
}

/** 기본 조건과 다른 필터가 하나라도 있는지(정렬 · 페이지 제외) — "조건 초기화" 를 보일지 */
export function hasFilter(query: ProductQuery): boolean {
  return (
    query.keyword !== '' ||
    query.categoryId !== null ||
    query.saleStatus !== null ||
    query.visible !== null ||
    query.availability !== null
  );
}
