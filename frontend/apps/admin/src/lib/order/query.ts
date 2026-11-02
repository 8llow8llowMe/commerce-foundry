import { ADMIN_ORDERS_PATH, ORDER_STATUSES, type OrderStatusCode } from './types';

/** 서버 기본값과 같다(1~100) */
export const PAGE_SIZE = 20;

/**
 * 화면 조건. null 은 "전체"(서버에 보내지 않는다).
 * 기간은 한국 날짜(`YYYY-MM-DD`)로 들고 양 끝을 포함한다 — 서버에는 KST 자정 기준 `[from, to + 1일)` UTC 시각으로 보낸다.
 * 결제수단 조건은 order 에 결제 정보가 없어 BE 가 결제 연동(#14) 뒤에 더한다(§2-16) — 그때 여기에 붙인다.
 */
export type OrderQuery = {
  status: OrderStatusCode | null;
  from: string | null;
  to: string | null;
  page: number;
};

export const DEFAULT_QUERY: OrderQuery = { status: null, from: null, to: null, page: 0 };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** 달력에 있는 날짜(`YYYY-MM-DD`)인지 — 2026-02-30 같은 값은 버린다 */
export function isDate(value: string | undefined): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().startsWith(value);
}

/**
 * 주소(query string) → 조건. 허용 값만 받고 나머지는 "전체"(기본값)로 본다 — 손으로 고친 주소가 400(`ORDER_146` · `198`)이 되지 않게.
 * 시작이 끝보다 늦으면 끝을 버린다(서버 `ORDER_146`). 페이지는 숫자 글자 6자리까지만 받는다.
 */
export function parseOrderQuery(params: SearchParams): OrderQuery {
  const status = first(params.status);
  const from = first(params.from);
  const to = first(params.to);
  const page = first(params.page);
  const fromDate = isDate(from) ? from : null;
  const toDate = isDate(to) && (fromDate === null || fromDate <= to) ? to : null;
  return {
    status: ORDER_STATUSES.find((item) => item === status) ?? null,
    from: fromDate,
    to: toDate,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/** 한국 날짜의 자정(KST) → UTC ISO 시각. `days` 만큼 뒤 날짜의 자정이다 */
export function kstMidnightIso(date: string, days = 0): string {
  const start = new Date(`${date}T00:00:00+09:00`);
  start.setUTCDate(start.getUTCDate() + days);
  return start.toISOString().replace('.000Z', 'Z');
}

/** 조건 → 주소 파라미터. 기본값은 빼서 주소를 짧게 둔다 */
function queryParams(query: OrderQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.status) params.set('status', query.status);
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  if (query.page > 0) params.set('page', String(query.page));
  return params;
}

/** 화면 주소 */
export function ordersHref(query: OrderQuery): string {
  const search = queryParams(query).toString();
  return search ? `/orders?${search}` : '/orders';
}

/** 주문 상세 화면 주소 */
export const orderHref = (orderId: string) => `/orders/${encodeURIComponent(orderId)}`;

/** BFF 경로. 기간은 UTC 시각으로 바꾸고, 페이지 · 크기는 늘 보낸다 */
export function ordersApiPath(query: OrderQuery): string {
  const params = new URLSearchParams();
  if (query.status) params.set('status', query.status);
  if (query.from) params.set('from', kstMidnightIso(query.from));
  if (query.to) params.set('to', kstMidnightIso(query.to, 1));
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_ORDERS_PATH}?${params.toString()}`;
}

/** 페이지를 뺀 조건이 하나라도 있는지 — "조건 초기화" 를 보일지 */
export function hasFilter(query: OrderQuery): boolean {
  return query.status !== null || query.from !== null || query.to !== null;
}
