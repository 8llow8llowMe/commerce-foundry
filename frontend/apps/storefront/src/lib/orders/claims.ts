import { apiFetch, type Money } from '@cf/api-client';
import type { Locale } from '@cf/ui';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { orderDetailPath, type OrderDetail, type OrderStatus } from './history';

/**
 * 내 주문의 클레임(반품 · 결제 후 취소 접수) 조회 — `GET /api/v1/orders/{orderId}/claims`(order-service, BE #609).
 * 정본은 `backend/docs/api-design-guide.md` §2-17. 아이디 내림차순(최신순) 전체 목록이고 없으면 `[]` 다. 남의 주문은 404 `ORDER_001`.
 * 결제 아이디 · 실패 코드 · 멱등 키는 내지 않는다. 환불 예정액 null 은 미산정(반품 검수 전)이고 0원이 아니다.
 */
export type OrderClaimLine = { orderItemId: string; quantity: number; reason: OrderStatus };

export type OrderClaim = {
  claimId: string;
  /** `RETURN` · `CANCELLATION` */
  type: OrderStatus;
  /** `REQUESTED` · `REFUNDED` · `COMPLETED` · `REFUND_FAILED` · `RESTORATION_FAILED` */
  status: OrderStatus;
  expectedRefund: Money | null;
  requestedAt: string;
  /** 주문 항목 아이디 오름차순 */
  items: OrderClaimLine[];
};

export const orderClaimsPath = (orderId: string) => `${orderDetailPath(orderId)}/claims`;

/**
 * 화면의 클레임 자리 상태. 빈 목록이면 자리를 두지 않는다.
 * `retrying` 은 실패 뒤 다시 읽는 중 — 버튼을 남긴 채 막아 포커스를 잃지 않고 연속 클릭도 막는다(배송 자리와 같다).
 */
export type ClaimsLoad =
  | { status: 'loading' }
  | { status: 'failed'; retrying: boolean }
  | { status: 'ready'; claims: OrderClaim[] };

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const isString = (value: unknown): value is string => typeof value === 'string';

function readMetadata(value: unknown): OrderStatus | null {
  if (!isRecord(value) || !isString(value.code) || !value.code) return null;
  return {
    code: value.code,
    name: isString(value.name) ? value.name : value.code,
    description: isString(value.description) ? value.description : '',
  };
}

function readMoney(value: unknown): Money | null | undefined {
  if (value === null) return null;
  if (!isRecord(value) || !Number.isInteger(value.amount) || !isString(value.currency)) {
    return undefined;
  }
  return { amount: value.amount as number, currency: value.currency } as Money;
}

function readLine(value: unknown): OrderClaimLine | null {
  if (!isRecord(value) || !isString(value.orderItemId) || !value.orderItemId) return null;
  const quantity = value.quantity;
  const reason = readMetadata(value.reason);
  if (!Number.isInteger(quantity) || (quantity as number) < 1 || !reason) return null;
  return { orderItemId: value.orderItemId, quantity: quantity as number, reason };
}

function readClaim(value: unknown): OrderClaim | null {
  if (!isRecord(value) || !isString(value.claimId) || !value.claimId) return null;
  const type = readMetadata(value.type);
  const status = readMetadata(value.status);
  const expectedRefund = readMoney(value.expectedRefund ?? null);
  const requestedAt = value.requestedAt;
  if (!type || !status || expectedRefund === undefined) return null;
  if (!isString(requestedAt) || !Number.isFinite(Date.parse(requestedAt))) return null;
  if (!Array.isArray(value.items)) return null;
  const items = value.items.map(readLine);
  if (items.some((line) => line === null)) return null;
  return {
    claimId: value.claimId,
    type,
    status,
    expectedRefund,
    requestedAt,
    items: items as OrderClaimLine[],
  };
}

/**
 * 응답(`dataBody`)을 클레임 목록으로 읽는다. 모양이 어긋난 클레임이 하나라도 있으면 null(실패로 보인다) —
 * 한 건을 조용히 빼면 고객이 접수한 클레임이 없는 것처럼 보인다. 순서는 서버 순서(최신순) 그대로다.
 */
export function parseOrderClaims(raw: unknown): OrderClaim[] | null {
  if (!Array.isArray(raw)) return null;
  const claims = raw.map(readClaim);
  return claims.some((claim) => claim === null) ? null : (claims as OrderClaim[]);
}

/**
 * 클레임을 읽어 화면 상태로 바꾼다. 실패는 kind 와 상관없이 모두 `failed` 다 — 401 · 404 는 같이 부른 주문 상세가 받아 화면을 바꾸고,
 * 읽기라 결과 모름 · 429 를 따로 다룰 것이 없다(배송 조회와 같다).
 */
export function readOrderClaims(orderId: string, fetchImpl?: typeof fetch): Promise<ClaimsLoad> {
  return apiFetch<unknown>(orderClaimsPath(orderId), {}, fetchImpl).then(
    (raw): ClaimsLoad => {
      const claims = parseOrderClaims(raw);
      return claims ? { status: 'ready', claims } : { status: 'failed', retrying: false };
    },
    (): ClaimsLoad => ({ status: 'failed', retrying: false }),
  );
}

/** 고객에게 보이는 클레임 상태 묶음 */
export type ClaimStatusGroup = 'requested' | 'refunded' | 'completed' | 'processing';

/**
 * 클레임 상태 → 고객용 묶음. 서버 이름(환불 실패 · 재고 복원 실패 등)은 운영자 말이라 두 언어 모두 사전 문구를 쓴다.
 * `REFUND_FAILED` · `RESTORATION_FAILED` 는 운영자 처리 대기라 "처리 중"이다(원인 문구는 어드민 몫, BE #609).
 * 모르는 상태도 "처리 중" — 끝났다고 잘못 알리지 않는다.
 */
export function claimStatusGroup(status: Pick<OrderStatus, 'code'>): ClaimStatusGroup {
  switch (status.code) {
    case 'REQUESTED':
      return 'requested';
    case 'REFUNDED':
      return 'refunded';
    case 'COMPLETED':
      return 'completed';
    default:
      return 'processing';
  }
}

export function claimStatusLabel(status: Pick<OrderStatus, 'code'>, t: Dictionary['orders']) {
  return t.claimStatuses[claimStatusGroup(status)];
}

/** 클레임 유형 이름 — 국문은 서버 이름, 영문은 사전(없는 코드는 서버 이름). 주문 상태와 같은 방식 */
export function claimTypeLabel(type: OrderStatus, t: Dictionary['orders'], locale: Locale) {
  return locale === 'ko' ? type.name : (t.claimTypes[type.code] ?? type.name);
}

/** 클레임 사유 이름 — 국문은 서버 이름, 영문은 취소 · 반품 사유 사전(없는 코드는 서버 이름) */
export function claimReasonLabel(reason: OrderStatus, t: Dictionary['orders'], locale: Locale) {
  if (locale === 'ko') return reason.name;
  const names: Record<string, string> = { ...t.cancelReasons, ...t.returnReasons };
  return names[reason.code] ?? reason.name;
}

/** 클레임 항목 표시 줄 — 주문 항목 아이디로 주문 상세의 상품명 · 옵션을 찾는다. 못 찾으면 이름은 null(화면이 대체 문구) */
export type ClaimLineView = {
  orderItemId: string;
  productName: string | null;
  optionName: string;
  quantity: number;
  reason: OrderStatus;
};

export function claimLineViews(
  claim: Pick<OrderClaim, 'items'>,
  order: Pick<OrderDetail, 'items'>,
): ClaimLineView[] {
  const byId = new Map(order.items.map((line) => [line.orderItemId, line]));
  return claim.items.map((item) => {
    const line = byId.get(item.orderItemId);
    return {
      orderItemId: item.orderItemId,
      productName: line?.productName ?? null,
      optionName: line?.optionName ?? '',
      quantity: item.quantity,
      reason: item.reason,
    };
  });
}
