import { ApiError, type Money } from '@cf/api-client';
import type { Locale } from '@cf/ui';
import type { OrderCreated } from '@/lib/checkout/order';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 고객 주문 내역 · 상세 · 결제 전 취소(order-service, BE #16 PR-1). 고객 전용이고 남의 주문은 없는 주문처럼 404 `ORDER_001` 이다.
 * 정본은 `backend/docs/api-design-guide.md` §2-17 · `backend/docs/order-flow-guide.md` §1-1.
 * 결제 뒤 취소 · 반품 · 구매 확정(#16 PR-2)과 배송 조회(#18)는 BE 전이라 화면에 자리도 두지 않는다.
 */
export const ORDER_HISTORY_PATH = '/api/v1/orders';
export const ORDER_HISTORY_PAGE_SIZE = 20;

export type OrderStatus = { code: string; name: string; description: string };

/** 주문 내역 한 줄 */
export type OrderSummary = {
  orderId: string;
  status: OrderStatus;
  /** "첫 상품명" 또는 "첫 상품명 외 N건"(서버가 국문으로 만든다). 항목이 없으면 null */
  orderName: string | null;
  itemCount: number;
  totalAmount: Money;
  /** 지금 취소 버튼을 보일지 — 결제 전(결제 대기)만 true */
  cancelable: boolean;
  paymentDueAt: string;
  createdAt: string;
};

/** 주문 상세 — 주문 생성 응답과 같은 스냅숏 + 취소 가능 여부 */
export type OrderDetail = OrderCreated & { cancelable: boolean };

export const orderListPath = (page: number) =>
  `${ORDER_HISTORY_PATH}?page=${page}&size=${ORDER_HISTORY_PAGE_SIZE}`;
export const orderDetailPath = (orderId: string) =>
  `${ORDER_HISTORY_PATH}/${encodeURIComponent(orderId)}`;
export const orderCancelPath = (orderId: string) => `${orderDetailPath(orderId)}/cancellation`;

/** 마이페이지 주문 상세 앱 경로 */
export const orderPageHref = (orderId: string) => `/mypage/orders/${encodeURIComponent(orderId)}`;

/** 취소 사유 코드(BE `OrderCancelReason`). 자유 입력은 받지 않는다 — 코드가 주문 이력 · 재고 해제 이벤트에 그대로 실린다 */
export const CANCEL_REASONS = [
  'CHANGE_OF_MIND',
  'ORDER_MISTAKE',
  'PAYMENT_ISSUE',
  'OTHER',
] as const;
export type CancelReason = (typeof CANCEL_REASONS)[number];

/**
 * 상태 이름 — 서버 이름은 국문 고정이라 영문 스토어는 사전에서 코드로 고른다(없는 코드는 서버 이름).
 * 국문은 서버 이름을 그대로 쓴다(상태가 더해져도 화면을 고치지 않는다).
 */
export function statusLabel(status: OrderStatus, t: Dictionary['orders'], locale: Locale): string {
  return locale === 'ko' ? status.name : (t.statuses[status.code] ?? status.name);
}

/**
 * "결제 이어하기"를 보일지 — 결제 대기이고 결제 기한 전일 때만. 기한이 지나면 결제 준비가 거절되고(`PAYMENT_004`)
 * 만료 스윕 전이라도 이어 결제할 수 없다. 시각 비교는 표시 판단일 뿐이고 결제 가능 여부는 결제 준비 API 가 정한다.
 */
export function canResumePayment(
  order: Pick<OrderSummary, 'status' | 'paymentDueAt'>,
  now: number,
): boolean {
  if (order.status.code !== 'PENDING_PAYMENT') return false;
  const due = Date.parse(order.paymentDueAt);
  return Number.isFinite(due) && due > now;
}

/** 다음 조각을 붙인다 — 그 사이 새 주문이 생기면 OFFSET 이 밀려 앞 조각의 주문이 다시 올 수 있어 아이디로 거른다 */
export function appendOrders(current: OrderSummary[], next: OrderSummary[]): OrderSummary[] {
  const seen = new Set(current.map((order) => order.orderId));
  return [...current, ...next.filter((order) => !seen.has(order.orderId))];
}

/** 주문 아이디 경로 조각 — 양의 정수 문자열만(Snowflake 라 JS number 로 바꾸지 않는다) */
export function readOrderPathId(value: string): string | null {
  return /^[1-9]\d{0,18}$/.test(value) ? value : null;
}

/**
 * 결제 전 취소 실패를 화면 처리로 가른다. 취소는 멱등이다(이미 취소됐으면 200) — 결과를 모르면 상세를 다시 읽어 확인하고,
 * 아직 취소할 수 있으면 다시 눌러도 된다. BE 에 멱등 키가 없다.
 * - signedOut: 401
 * - reload: 상세를 다시 읽는다 — 409 `ORDER_020`(결제 완료 · 만료가 먼저 커밋 — `@Version` 경합에서 진 경우 포함) ·
 *   404 `ORDER_001` · 결과를 모르는 실패(502/504 · 5xx · 연결 실패 · 봉투 아님)
 * - retry: 반영되지 않았다 — 429(쿨다운) · 503 · 그 밖의 4xx
 */
export type CancelFailure =
  { kind: 'signedOut' } | { kind: 'reload'; message: string } | { kind: 'retry'; message: string };

const UNKNOWN_KINDS = new Set<ApiError['kind']>(['network', 'invalidResponse', 'unknownOutcome']);

export function cancelFailure(cause: unknown, t: Dictionary['orders']): CancelFailure {
  if (!(cause instanceof ApiError)) return { kind: 'reload', message: t.cancelUnknown };
  if (cause.kind === 'unauthorized') return { kind: 'signedOut' };
  const code = cause.code ?? '';
  // 서버 문구는 국문 고정이고 409 는 상태 이름이 섞여 있어 사전 문구만 쓴다
  const message = t.cancelErrors[code];
  if (cause.kind === 'rateLimited') {
    return { kind: 'retry', message: t.cancelErrors.GATEWAY_006 ?? t.cancelFailed };
  }
  if (code === 'ORDER_020' || cause.kind === 'notFound') {
    return { kind: 'reload', message: message ?? t.cancelFailed };
  }
  if (UNKNOWN_KINDS.has(cause.kind) || (cause.kind === 'server' && (cause.status ?? 500) >= 500)) {
    return { kind: 'reload', message: t.cancelUnknown };
  }
  return { kind: 'retry', message: message ?? t.cancelFailed };
}
