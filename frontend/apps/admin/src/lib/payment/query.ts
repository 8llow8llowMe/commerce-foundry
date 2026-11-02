import { ADMIN_PAYMENTS_PATH, PAYMENT_STATUSES, type PaymentStatusCode } from './types';

/** 서버 기본값과 같다(1~100) */
export const PAGE_SIZE = 20;

/**
 * 화면 조건. null 은 "전체"(서버에 보내지 않는다). 서버는 셋을 AND 로 건다.
 * `orderSynced` 는 주문 성공 회신을 받았는지다 — false 가 곧 복구 대상은 아니다.
 */
export type PaymentQuery = {
  status: PaymentStatusCode | null;
  orderId: string | null;
  orderSynced: boolean | null;
  page: number;
};

export const DEFAULT_QUERY: PaymentQuery = {
  status: null,
  orderId: null,
  orderSynced: null,
  page: 0,
};

/** 확인이 필요한 결제를 바로 거는 조건 — 결제 확인 중 · 늦은 승인 */
export const PRESETS: { label: string; query: PaymentQuery }[] = [
  { label: '결제 확인 중', query: { ...DEFAULT_QUERY, status: 'UNKNOWN' } },
  { label: '늦은 승인', query: { ...DEFAULT_QUERY, status: 'LATE_APPROVED' } },
  {
    label: '결제 완료 · 주문 회신 없음',
    query: { ...DEFAULT_QUERY, status: 'DONE', orderSynced: false },
  },
];

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

const LONG_MAX = '9223372036854775807';

/** 서버 `@Positive Long` 에 맞는 주문 아이디인지 — 0 · 음수 · 앞자리 0 · Long 범위 밖은 버린다(서버 `102`) */
export function isOrderId(value: string | undefined): value is string {
  if (!value || !/^[1-9]\d{0,18}$/.test(value)) return false;
  return value.length < LONG_MAX.length || value <= LONG_MAX;
}

/**
 * 주소(query string) → 조건. 허용 값만 받고 나머지는 "전체"(기본값)로 본다 — 손으로 고친 주소가 400 이 되지 않게.
 * 페이지는 숫자 글자 6자리까지만 받는다.
 */
export function parsePaymentQuery(params: SearchParams): PaymentQuery {
  const status = first(params.status);
  const orderId = first(params.orderId)?.trim();
  const orderSynced = first(params.orderSynced);
  const page = first(params.page);
  return {
    status: PAYMENT_STATUSES.find((item) => item === status) ?? null,
    orderId: isOrderId(orderId) ? orderId : null,
    orderSynced: orderSynced === 'true' ? true : orderSynced === 'false' ? false : null,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/** 조건 → 주소 파라미터. 기본값은 빼서 주소를 짧게 둔다 */
function queryParams(query: PaymentQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.status) params.set('status', query.status);
  if (query.orderId) params.set('orderId', query.orderId);
  if (query.orderSynced !== null) params.set('orderSynced', String(query.orderSynced));
  if (query.page > 0) params.set('page', String(query.page));
  return params;
}

/** 화면 주소 */
export function paymentsHref(query: PaymentQuery): string {
  const search = queryParams(query).toString();
  return search ? `/payments?${search}` : '/payments';
}

/** BFF 경로. 페이지 · 크기는 늘 보낸다 */
export function paymentsApiPath(query: PaymentQuery): string {
  const params = queryParams({ ...query, page: 0 });
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_PAYMENTS_PATH}?${params.toString()}`;
}

/** 페이지를 뺀 조건이 하나라도 있는지 — "조건 초기화" 를 보일지 */
export function hasFilter(query: PaymentQuery): boolean {
  return query.status !== null || query.orderId !== null || query.orderSynced !== null;
}

/** 프리셋과 지금 조건이 같은지(페이지는 보지 않는다) */
export function isPreset(query: PaymentQuery, preset: PaymentQuery): boolean {
  return (
    query.status === preset.status &&
    query.orderId === preset.orderId &&
    query.orderSynced === preset.orderSynced
  );
}
