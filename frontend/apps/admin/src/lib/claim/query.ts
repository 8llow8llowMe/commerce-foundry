import { isOrderId } from '@/lib/payment/query';
import {
  ADMIN_CLAIMS_PATH,
  CLAIM_STATUSES,
  CLAIM_TYPES,
  type ClaimStatusCode,
  type ClaimTypeCode,
} from './types';

/** 서버 기본값과 같다(1~100) */
export const PAGE_SIZE = 20;

/** 화면 조건. null 은 "전체"(서버에 보내지 않는다). 서버는 셋을 AND 로 건다 */
export type ClaimQuery = {
  type: ClaimTypeCode | null;
  status: ClaimStatusCode | null;
  orderId: string | null;
  page: number;
};

export const DEFAULT_QUERY: ClaimQuery = { type: null, status: null, orderId: null, page: 0 };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/**
 * 주소(query string) → 조건. 허용 값만 받고 나머지는 "전체"(기본값)로 본다 — 손으로 고친 주소가 400 이 되지 않게.
 * 페이지는 숫자 글자 6자리까지만 받는다(page × 20 이 서버 상한 `ORDER_167` 을 넘지 않는다).
 */
export function parseClaimQuery(params: SearchParams): ClaimQuery {
  const type = first(params.type);
  const status = first(params.status);
  const orderId = first(params.orderId)?.trim();
  const page = first(params.page);
  return {
    type: CLAIM_TYPES.find((item) => item === type) ?? null,
    status: CLAIM_STATUSES.find((item) => item === status) ?? null,
    orderId: isOrderId(orderId) ? orderId : null,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/** 조건 → 주소 파라미터. 기본값은 빼서 주소를 짧게 둔다 */
function queryParams(query: ClaimQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.type) params.set('type', query.type);
  if (query.status) params.set('status', query.status);
  if (query.orderId) params.set('orderId', query.orderId);
  if (query.page > 0) params.set('page', String(query.page));
  return params;
}

/** 화면 주소 */
export function claimsHref(query: ClaimQuery): string {
  const search = queryParams(query).toString();
  return search ? `/claims?${search}` : '/claims';
}

export const claimHref = (claimId: string) => `/claims/${encodeURIComponent(claimId)}`;

/** BFF 경로. 페이지 · 크기는 늘 보낸다 */
export function claimsApiPath(query: ClaimQuery): string {
  const params = queryParams({ ...query, page: 0 });
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${ADMIN_CLAIMS_PATH}?${params.toString()}`;
}

/** 페이지를 뺀 조건이 하나라도 있는지 — "조건 초기화" 를 보일지 */
export function hasFilter(query: ClaimQuery): boolean {
  return query.type !== null || query.status !== null || query.orderId !== null;
}
