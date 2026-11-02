import type { Money } from '@cf/api-client';
import type { CodeName } from '@/lib/product/detail';

/**
 * 어드민 주문 관리 API(#17 PR-1, order-service `OrderAdminController`, `backend/docs/api-design-guide.md` §2-16).
 * 결제 · 배송(송장) 요약과 클레임 처리는 아직 API 가 없다(#14 · #18 PR-B2 · #15 · #16) — 화면에도 자리를 두지 않는다. 송장 등록은 `shipment.ts`.
 */
export const ADMIN_ORDERS_PATH = '/api/v1/admin/orders';
export const adminOrderPath = (orderId: string) =>
  `${ADMIN_ORDERS_PATH}/${encodeURIComponent(orderId)}`;
export const adminOrderStatusPath = (orderId: string) => `${adminOrderPath(orderId)}/status`;
export const ADMIN_ORDER_STATUS_CHANGES_PATH = `${ADMIN_ORDERS_PATH}/status-changes`;

/** 서버 `OrderStatusFilter` — 목록 조건에만 쓴다. 전이 가능 여부는 화면이 정하지 않는다(상세의 `nextStatuses` · 일괄 결과) */
export const ORDER_STATUSES = [
  'PENDING_PAYMENT',
  'EXPIRED',
  'PAID',
  'MAKING',
  'PREPARING',
  'SHIPPING',
  'DELIVERED',
  'PURCHASE_CONFIRMED',
  'CANCEL_REQUESTED',
  'CANCELED',
  'RETURN_REQUESTED',
  'RETURNED',
] as const;

export type OrderStatusCode = (typeof ORDER_STATUSES)[number];

/** 조건 고르기용 이름 — 서버 `OrderStatus` displayName 과 같다. 응답에 온 상태는 응답의 `name` 을 그대로 보인다 */
export const ORDER_STATUS_LABELS: Record<OrderStatusCode, string> = {
  PENDING_PAYMENT: '결제 대기',
  EXPIRED: '주문 만료',
  PAID: '결제 완료',
  MAKING: '제작 중',
  PREPARING: '상품 준비중',
  SHIPPING: '배송 중',
  DELIVERED: '배송 완료',
  PURCHASE_CONFIRMED: '구매 확정',
  CANCEL_REQUESTED: '취소 요청',
  CANCELED: '취소 완료',
  RETURN_REQUESTED: '반품 요청',
  RETURNED: '반품 완료',
};

/**
 * 일괄 변경으로 고를 수 있는 목표 상태. 어드민이 직접 바꾸는 전이의 목표(`AdminStatusChangePolicy`) 중 배송 완료를 뺀 것이다 —
 * 배송 완료는 한 건씩만(일괄은 400 `ORDER_153`). 주문마다 바뀔 수 있는지는 서버가 행별 결과로 알려 준다.
 */
export const BULK_TARGET_STATUSES = ['PREPARING', 'MAKING'] as const satisfies OrderStatusCode[];
export type BulkTargetStatus = (typeof BULK_TARGET_STATUSES)[number];

/** 서버 `@Size(max = 200)` */
export const REASON_MAX = 200;

/** `OrderAdminItem` */
export type OrderListItem = {
  orderId: string;
  memberId: string;
  status: CodeName;
  orderName: string;
  /** 최소 화폐 단위 — `currency` 와 묶어 `formatMoney` 로만 보인다 */
  totalAmount: number;
  currency: Money['currency'];
  /** 마스킹(홍*동) */
  recipientName: string;
  createdAt: string;
  paymentDueAt: string;
};

export type OrderLine = {
  skuId: string;
  productId: string;
  productName: string;
  optionName: string;
  unitPrice: Money;
  quantity: number;
  lineAmount: Money;
  customOrder: boolean;
  customOptions: { fieldKey: string; value: string }[];
};

/** 마스킹된 배송지 — 상세 주소 · 요청 사항은 있으면 `****` 다 */
export type OrderShippingAddress = {
  recipientName: string;
  recipientPhone: string;
  countryCode: string;
  postalCode: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string | null;
  stateProvince: string | null;
  deliveryMemo: string | null;
};

export type OrderHistory = {
  /** 주문 접수 줄이면 null */
  fromStatus: CodeName | null;
  toStatus: CodeName;
  /** 고객 · 관리자 · 자동 처리 · 연동 처리 */
  changedBy: CodeName;
  changedById: string | null;
  reason: string | null;
  changedAt: string;
};

/** `OrderAdminResponse` — 상세 · 상태 변경 응답 */
export type OrderDetail = {
  orderId: string;
  memberId: string;
  status: CodeName;
  /** 지금 이 화면에서 바꿀 수 있는 다음 상태 — 버튼은 이것만 그린다 */
  nextStatuses: CodeName[];
  currency: Money['currency'];
  items: OrderLine[];
  amounts: { itemsAmount: Money; shippingFee: Money; totalAmount: Money };
  shippingAddress: OrderShippingAddress;
  /** 오래된 것부터 */
  histories: OrderHistory[];
  paymentDueAt: string;
  createdAt: string;
};

export type StatusChangeRequest = { status: string; reason: string | null };

export type BulkStatusChangeRequest = {
  orderIds: string[];
  status: BulkTargetStatus;
  reason: string | null;
};

export type BulkResultCode = 'CHANGED' | 'UNCHANGED' | 'FAILED';

/** `OrderStatusBulkChangeResponse` — 결과는 요청 순서 */
export type BulkStatusChangeResponse = {
  changedCount: number;
  unchangedCount: number;
  failedCount: number;
  results: {
    orderId: string;
    result: BulkResultCode;
    errorCode: string | null;
    message: string | null;
  }[];
};

/** 보낸 일괄 요청과 그 결과. "실패만 다시 시도" 는 같은 상태 · 사유로 실패한 주문만 보낸다 */
export type BulkOutcome = {
  request: BulkStatusChangeRequest;
  response: BulkStatusChangeResponse;
  /** 결과 줄에 보일 주문 이름 — 보낼 때의 목록에서 잡는다(다시 불러온 목록에 없어도 보이게) */
  names: Record<string, string>;
};
