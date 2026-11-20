/**
 * 어드민 출고 묶음 · 포장 검수(#364, BE #363 · #739 · #740, `backend/docs/api-design-guide.md` §2-20).
 * 묶음 대상 판정 · 피킹 합계 · 검수 성공 여부는 모두 서버가 정한다. 화면은 센 수량을 보내고 결과를 보일 뿐이다.
 */
export const PICKING_BATCHES_PATH = '/api/v1/admin/deliveries/picking-batches';
export const pickingBatchPath = (batchId: string) =>
  `${PICKING_BATCHES_PATH}/${encodeURIComponent(batchId)}`;
export const pickingListPath = (batchId: string) => `${pickingBatchPath(batchId)}/picking-list`;
export const pickingBulkCheckPath = (batchId: string) => `${pickingBatchPath(batchId)}/checks`;
export const pickingOrderCheckPath = (batchId: string, orderId: string) =>
  `${pickingBatchPath(batchId)}/orders/${encodeURIComponent(orderId)}/checks`;

/** 어드민 화면 경로 — 묶음 ID 가 주소에 있어 새로고침 · 복귀해도 서버 상태를 다시 읽는다 */
export const pickingHref = (batchId: string) => `/picking/${encodeURIComponent(batchId)}`;

/** 서버 `@Size(min = 1, max = 20)` — 묶음 하나에 담는 주문 수 */
export const BATCH_ORDER_MAX = 20;
/** 한 주문에 셀 수 있는 수량 상한. 서버는 양의 정수만 받는다 — 화면 입력 실수(자릿수 추가)를 먼저 막는 값이다 */
export const COUNT_MAX = 9999;

export type Reason = { code: string; name: string; description: string };

/** 검수 결과 사유 코드 — 성공은 `NONE` */
export type CheckReasonCode =
  | 'NONE'
  | 'SHORTAGE'
  | 'EXCESS'
  | 'WRONG_SKU'
  | 'ALREADY_CHECKED'
  | 'ORDER_NOT_IN_BATCH'
  | (string & {});

export type SkuQuantity = { skuId: string; quantity: number };

export type PickingOrder = {
  orderId: string;
  /** 묶음을 만들 때의 주문 상태 코드(문자열) — 지금 상태가 아니다 */
  status: string;
  countryCode: string;
  /** 검수 성공 여부 */
  checked: boolean;
  /** SKU 별 주문 수량 — 옵션 ID 오름차순 */
  quantities: SkuQuantity[];
};

export type PickingCheck = {
  orderId: string;
  successful: boolean;
  reason: Reason;
  quantities: SkuQuantity[];
};

/** `PickingBatchAdminResponse` */
export type PickingBatch = {
  batchId: string;
  version: number;
  inspectionComplete: boolean;
  totalQuantity: number;
  pickingQuantities: SkuQuantity[];
  orders: PickingOrder[];
  exclusions: { orderId: string; reason: Reason }[];
  /** 기록 순서 */
  checks: PickingCheck[];
};

export type CheckRequest = { quantities: Record<string, number> };

export type BulkCheckRequest = { items: { orderId: string; check: CheckRequest }[] };

/** `PickingBulkCheckAdminResponse` — 결과는 요청 순서 */
export type BulkCheckResponse = {
  batchId: string;
  results: { orderId: string; successful: boolean; reason: Reason; retryable: boolean }[];
};
