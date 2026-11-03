import { ApiError, type Money } from '@cf/api-client';
import type { Locale } from '@cf/ui';
import type { OrderCreated } from '@/lib/checkout/order';
import type { QuoteLine } from '@/lib/checkout/quote';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 고객 주문 내역 · 상세 · 결제 전 취소 · 구매 확정 · 부분 반품 접수(order-service, BE #16). 고객 전용이고 남의 주문은 없는 주문처럼 404 `ORDER_001` 이다.
 * 정본은 `backend/docs/api-design-guide.md` §2-17 · `backend/docs/order-flow-guide.md` §1-1.
 * 결제 뒤 취소 · 환불은 BE 전이라 화면에 자리도 두지 않는다. 배송 조회는 `delivery.ts`(BE #18 PR-B2).
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

/**
 * 주문 상세 항목 — 주문 시점 스냅숏 + 주문 항목 아이디. 아이디는 반품 접수 본문에 싣는다.
 * 고객 상세 응답에 아직 없어(BE #609) 없으면 그 항목은 반품 대상에서 빠진다.
 */
export type OrderDetailLine = QuoteLine & { orderItemId?: string };

/** 주문 상세 — 주문 생성 응답과 같은 스냅숏 + 취소 가능 여부 */
export type OrderDetail = Omit<OrderCreated, 'items'> & {
  items: OrderDetailLine[];
  cancelable: boolean;
};

export const orderListPath = (page: number) =>
  `${ORDER_HISTORY_PATH}?page=${page}&size=${ORDER_HISTORY_PAGE_SIZE}`;
export const orderDetailPath = (orderId: string) =>
  `${ORDER_HISTORY_PATH}/${encodeURIComponent(orderId)}`;
export const orderCancelPath = (orderId: string) => `${orderDetailPath(orderId)}/cancellation`;
export const orderPurchaseConfirmationPath = (orderId: string) =>
  `${orderDetailPath(orderId)}/purchase-confirmation`;
export const orderReturnPath = (orderId: string) => `${orderDetailPath(orderId)}/returns`;

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

/** 반품 접수 사유 코드(BE `ClaimItem.Reason`). 비용 부담 정책을 뜻하지 않는다 — 판매자가 확인한 뒤 정한다 */
export const RETURN_REASONS = ['CHANGE_OF_MIND', 'DEFECTIVE', 'WRONG_ITEM'] as const;
export type ReturnReason = (typeof RETURN_REASONS)[number];

/** 반품 접수 결과(BE `OrderReturnResponse`). 신규 201 · 같은 키 · 같은 본문 재생 200. 환불 금액은 미산정(null) — 0원이 아니다 */
export type OrderReturnResult = {
  claimId: string;
  orderId: string;
  status: OrderStatus;
  items: { orderItemId: string; quantity: number; reason: OrderStatus }[];
  expectedRefund: Money | null;
  requestedAt: string;
};

/** 반품 접수 본문 */
export type OrderReturnBody = {
  items: { orderItemId: string; quantity: number; reason: ReturnReason }[];
};

/** 반품으로 고를 수 있는 항목 — 주문 항목 아이디가 있는 줄만(BE #609 전에는 없을 수 있다) */
export function returnableLines(
  order: Pick<OrderDetail, 'items'>,
): (OrderDetailLine & { orderItemId: string })[] {
  return order.items.filter(
    (line): line is OrderDetailLine & { orderItemId: string } => !!line.orderItemId,
  );
}

/**
 * 반품 접수 본문 — 고른 항목(주문 항목 아이디 → 수량)을 주문 순서대로 싣고 사유는 모두 같은 것을 쓴다.
 * 고른 항목이 없거나 사유가 없으면 null(버튼을 막는다). 수량은 1 ~ 주문 수량으로 자른다 —
 * 이미 접수한 수량을 뺀 잔여 수량은 서버만 알아서 넘으면 409 `ORDER_030` 이다. 금액은 싣지 않는다.
 */
export function returnRequestBody(
  order: Pick<OrderDetail, 'items'>,
  selected: Record<string, number>,
  reason: ReturnReason | null,
): OrderReturnBody | null {
  if (!reason) return null;
  const items = returnableLines(order).flatMap((line) => {
    const quantity = selected[line.orderItemId];
    if (!quantity) return [];
    const max = Math.min(line.quantity, 99);
    return [
      { orderItemId: line.orderItemId, quantity: Math.min(Math.max(quantity, 1), max), reason },
    ];
  });
  return items.length > 0 ? { items } : null;
}

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

/**
 * 주문 상세에서 보일 액션 — BE 허용 전이 그대로다. 배송 중 · 확정 뒤에는 어느 쪽도 없다.
 * - cancel: 서버의 `cancelable`(결제 전 · 결제 대기만 true). 화면에서 상태로 다시 추측하지 않는다
 * - confirmPurchase: 배송 완료(`DELIVERED → PURCHASE_CONFIRMED` 만 허용). 이미 확정됐으면 보이지 않는다
 * - requestReturn: 배송 완료 · 반품 요청(추가 접수)에서, 반품으로 고를 항목이 있을 때만. 남은 수량은 서버가 판단한다
 */
export function orderActions(order: Pick<OrderDetail, 'status' | 'cancelable' | 'items'>): {
  cancel: boolean;
  confirmPurchase: boolean;
  requestReturn: boolean;
} {
  const code = order.status.code;
  return {
    cancel: order.cancelable,
    confirmPurchase: code === 'DELIVERED',
    requestReturn:
      (code === 'DELIVERED' || code === 'RETURN_REQUESTED') && returnableLines(order).length > 0,
  };
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
 * - reload: 상세를 다시 읽는다 — 409 `ORDER_020`(결제 완료 · 만료가 먼저 커밋 — `@Version` 경합에서 진 경우 포함) · `ORDER_025` ·
 *   404 `ORDER_001` · 결과를 모르는 실패(502/504 · 5xx · 연결 실패 · 봉투 아님)
 * - retry: 반영되지 않았다 — 429(쿨다운) · 503 · 그 밖의 4xx
 */
export type OrderActionFailure =
  { kind: 'signedOut' } | { kind: 'reload'; message: string } | { kind: 'retry'; message: string };

const UNKNOWN_KINDS = new Set<ApiError['kind']>(['network', 'invalidResponse', 'unknownOutcome']);

export function cancelFailure(cause: unknown, t: Dictionary['orders']): OrderActionFailure {
  return actionFailure(cause, {
    errors: t.cancelErrors,
    failed: t.cancelFailed,
    unknown: t.cancelUnknown,
  });
}

/**
 * 구매 확정 실패를 화면 처리로 가른다. 구매 확정도 멱등이다(이미 확정됐으면 200, 이력을 더하지 않는다) — 결과를 모르면
 * 상세를 다시 읽어 확인한다. 배송 완료로 남아 있으면 다시 눌러도 된다. BE 에 멱등 키가 없다.
 * - reload: 409 `ORDER_020`(배송 완료가 아님 — 반품 요청 등으로 이미 바뀜) · 409 `ORDER_025`(다른 전이가 먼저 커밋) ·
 *   404 `ORDER_001` · 결과를 모르는 실패. 자동 구매 확정과 겹치면 BE 가 다시 읽어 200 으로 돌려준다
 * - signedOut · retry 는 취소와 같다
 */
export function confirmFailure(cause: unknown, t: Dictionary['orders']): OrderActionFailure {
  return actionFailure(cause, {
    errors: t.confirmErrors,
    failed: t.confirmFailed,
    unknown: t.confirmUnknown,
  });
}

/** 상태를 바꾸는 주문 액션 실패 공통 분류 — 경합(409 `ORDER_020` · `ORDER_025`) · 404 · 결과 모름은 다시 읽는다 */
function actionFailure(
  cause: unknown,
  copy: { errors: Record<string, string>; failed: string; unknown: string },
): OrderActionFailure {
  if (!(cause instanceof ApiError)) return { kind: 'reload', message: copy.unknown };
  if (cause.kind === 'unauthorized') return { kind: 'signedOut' };
  const code = cause.code ?? '';
  // 서버 문구는 국문 고정이고 409 는 상태 이름이 섞여 있어 사전 문구만 쓴다
  const message = copy.errors[code];
  if (cause.kind === 'rateLimited') {
    return { kind: 'retry', message: copy.errors.GATEWAY_006 ?? copy.failed };
  }
  if (code === 'ORDER_020' || code === 'ORDER_025' || cause.kind === 'notFound') {
    return { kind: 'reload', message: message ?? copy.failed };
  }
  if (UNKNOWN_KINDS.has(cause.kind) || (cause.kind === 'server' && (cause.status ?? 500) >= 500)) {
    return { kind: 'reload', message: copy.unknown };
  }
  return { kind: 'retry', message: message ?? copy.failed };
}

/**
 * 반품 접수 실패를 화면 처리로 가른다. 반품은 `Idempotency-Key` 가 필수다 — 같은 키 · 같은 본문은 기존 접수를 200 으로 재생한다.
 * - signedOut: 401
 * - reload: 상세를 다시 읽는다 — 409 `ORDER_020`(구매 확정 등으로 반품할 수 없는 상태) · 404 `ORDER_001`
 * - retry + sameKey: 결과를 모르거나 반영되지 않았다 — 같은 키로 다시 누르면 접수됐으면 재생, 아니면 새로 접수된다.
 *   결과 모름(502/504 · 5xx · 연결 실패 · 봉투 아님) · 409 `ORDER_025`(경합) · 처리 중(409) · 429 · 503
 * - retry + 새 키: 본문을 고쳐야 한다 — 400 `ORDER_029` · 409 `ORDER_030`(잔여 수량 초과) · `ORDER_031` · 그 밖의 4xx
 */
export type ReturnFailure =
  | { kind: 'signedOut' }
  | { kind: 'reload'; message: string }
  | { kind: 'retry'; message: string; sameKey: boolean };

export function returnFailure(cause: unknown, t: Dictionary['orders']): ReturnFailure {
  if (!(cause instanceof ApiError))
    return { kind: 'retry', message: t.returnUnknown, sameKey: true };
  if (cause.kind === 'unauthorized') return { kind: 'signedOut' };
  const code = cause.code ?? '';
  // 서버 문구는 국문 고정이고 409 는 상태 이름이 섞여 있어 사전 문구만 쓴다
  const message = t.returnErrors[code];
  if (cause.kind === 'rateLimited') {
    return { kind: 'retry', message: t.returnErrors.GATEWAY_006 ?? t.returnFailed, sameKey: true };
  }
  if (code === 'ORDER_020' || cause.kind === 'notFound') {
    return { kind: 'reload', message: message ?? t.returnFailed };
  }
  if (UNKNOWN_KINDS.has(cause.kind) || (cause.kind === 'server' && (cause.status ?? 500) >= 500)) {
    return { kind: 'retry', message: t.returnUnknown, sameKey: true };
  }
  if (
    code === 'ORDER_025' ||
    cause.kind === 'unavailable' ||
    (cause.kind === 'conflict' && !code.startsWith('ORDER_'))
  ) {
    return { kind: 'retry', message: message ?? t.returnFailed, sameKey: true };
  }
  return { kind: 'retry', message: message ?? t.returnFailed, sameKey: false };
}
