import type { Money } from '@cf/api-client';
import type { CodeName } from '@/lib/product/detail';

/**
 * 어드민 클레임 API(#17 · BE PR #574 · #680 · #686, order-service `ClaimAdminController`) — 목록 · 상세 조회와
 * 결제 후 취소 "다시 시도"(`POST {claimId}/retries`). 승인 · 거절 · 수동 환불 확정은 아직 API 가 없다(BE #17 잔여) —
 * 화면에도 자리를 두지 않는다. BFF 는 범용 프록시(`app/api/[...path]`)가 그대로 넘긴다.
 */
export const ADMIN_CLAIMS_PATH = '/api/v1/admin/claims';
export const adminClaimPath = (claimId: string) =>
  `${ADMIN_CLAIMS_PATH}/${encodeURIComponent(claimId)}`;
export const adminClaimRetryPath = (claimId: string) => `${adminClaimPath(claimId)}/retries`;

/** 서버 `ClaimAdminFilter.Type` — 목록 조건에만 쓴다(다른 값은 400). 취소는 결제 뒤 출고 전 주문의 취소 클레임이다 */
export const CLAIM_TYPES = ['RETURN', 'CANCELLATION'] as const;
export type ClaimTypeCode = (typeof CLAIM_TYPES)[number];

/** 서버 `ClaimAdminFilter.Status` — 목록 조건에만 쓴다(다른 값은 400). 실패 둘은 운영자 확인이 필요한 상태다 */
export const CLAIM_STATUSES = [
  'REQUESTED',
  'REFUNDED',
  'COMPLETED',
  'REFUND_FAILED',
  'RESTORATION_FAILED',
] as const;
export type ClaimStatusCode = (typeof CLAIM_STATUSES)[number];

/** 조건 고르기용 이름 — 서버 `Claim.Type` · `Claim.Status` displayName 과 같다. 응답에 온 값은 응답의 `name` 을 그대로 보인다 */
export const CLAIM_TYPE_LABELS: Record<ClaimTypeCode, string> = {
  RETURN: '반품',
  CANCELLATION: '취소',
};
export const CLAIM_STATUS_LABELS: Record<ClaimStatusCode, string> = {
  REQUESTED: '접수',
  REFUNDED: '환불 완료',
  COMPLETED: '완료',
  REFUND_FAILED: '환불 실패',
  RESTORATION_FAILED: '재고 복원 실패',
};

/** `ClaimAdminItem`. 시각은 UTC ISO 다 */
export type ClaimListItem = {
  claimId: string;
  orderId: string;
  type: CodeName;
  status: CodeName;
  /** 예상 환불액 — null 은 미산정이며 0원과 다르다. 화면은 계산하지 않고 그대로 보인다 */
  expectedRefund: Money | null;
  requestedAt: string;
};

/**
 * `ClaimAdminLineItem`. 상품 이름 · 옵션은 내려오지 않는다 — 주문 상세 응답에도 주문 항목 아이디가 없어
 * 화면에서 이어 붙이지 않는다(순서로 짐작하지 않는다). 상품은 주문 상세에서 확인한다.
 */
export type ClaimLine = {
  orderItemId: string;
  quantity: number;
  reason: CodeName;
};

/**
 * `ClaimAdminRecoveryItem` — 결제 후 취소 처리 상황(#17 A2b). 다시 시도할 수 있는지의 정본은 서버의 `retryable` 이다 —
 * 결제사 거절 환불(`REFUND_REJECTED`) · 결제 취소 키 기한(접수 + 14일)이 lease 안에 끝나는 건은 서버가 false 로 준다.
 */
export type ClaimRecovery = {
  /** NO_ACTION · AUTO_PROCESSING · REFUND_UNCONFIRMED · REFUND_REJECTED · RESTORATION_UNCONFIRMED · RESTORATION_REJECTED · ORDER_STATUS_MISMATCH. name · description 을 그대로 보인다 */
  situation: CodeName;
  /** 자동 처리가 다시 확인할 시각(UTC ISO). 자동 처리 중이 아니면 null */
  nextAutomaticAttemptAt: string | null;
  retryable: boolean;
  /** 화면 버전 — 다시 시도 본문의 `expectedVersion` 으로 그대로 보낸다(멱등 키 대신 낙관적 버전으로 겹친 요청을 막는다) */
  version: number;
};

/** `ClaimAdminResponse` — 항목은 주문 항목 아이디 오름차순. 다시 시도 응답도 같은 모양(현재 상세)이다 */
export type ClaimDetail = ClaimListItem & { items: ClaimLine[]; recovery: ClaimRecovery };
