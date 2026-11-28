import type { Money } from '@cf/api-client';
import type { CodeName } from '@/lib/product/detail';

/**
 * 어드민 클레임 API(#17 · BE PR #574 · #680 · #686 · #696, order-service `ClaimAdminController`) — 목록 · 상세 조회,
 * 결제 후 취소 "다시 시도"(`POST {claimId}/retries`), 수동 환불 확정(`POST {claimId}/refund-confirmation`) ·
 * 재고 복원 없이 종결(`POST {claimId}/completion`), 반품 승인 · 거절(`POST {claimId}/return-approval` · `return-rejection`, #17 B2c-2).
 * 결제 후 취소 거절은 아직 API 가 없다(BE #17 잔여) — 화면에도 자리를 두지 않는다. BFF 는 범용 프록시(`app/api/[...path]`)가 그대로 넘긴다.
 */
export const ADMIN_CLAIMS_PATH = '/api/v1/admin/claims';
export const adminClaimPath = (claimId: string) =>
  `${ADMIN_CLAIMS_PATH}/${encodeURIComponent(claimId)}`;
export const adminClaimRetryPath = (claimId: string) => `${adminClaimPath(claimId)}/retries`;
/** 수동 환불 확정(#17 A3, SETTLEMENT_MANAGE) — 결제사를 부르지 않고 기록만 한다 */
export const adminClaimRefundConfirmationPath = (claimId: string) =>
  `${adminClaimPath(claimId)}/refund-confirmation`;
/** 재고 복원 없이 종결(#17 A3, SETTLEMENT_MANAGE) — 재고 서비스를 부르지 않고 닫기만 한다 */
export const adminClaimCompletionPath = (claimId: string) =>
  `${adminClaimPath(claimId)}/completion`;
/** 반품 승인(#17 B2c-2, ORDER_MANAGE) — 같은 요청에서 결제사 환불 · 재고 복원까지 이어 간다 */
export const adminClaimReturnApprovalPath = (claimId: string) =>
  `${adminClaimPath(claimId)}/return-approval`;
/** 반품 거절(#17 B2c-2, ORDER_MANAGE) — 환불 없이 닫는다 */
export const adminClaimReturnRejectionPath = (claimId: string) =>
  `${adminClaimPath(claimId)}/return-rejection`;

/** 서버 `ClaimAdminFilter.Type` — 목록 조건에만 쓴다(다른 값은 400). 취소는 결제 뒤 출고 전 주문의 취소 클레임이다 */
export const CLAIM_TYPES = ['RETURN', 'CANCELLATION'] as const;
export type ClaimTypeCode = (typeof CLAIM_TYPES)[number];

/**
 * 서버 `ClaimAdminFilter.Status` — 목록 조건에만 쓴다(다른 값은 400). 서버 순서와 같다. 실패 둘은 운영자 확인이 필요한 상태다.
 * 승인 · 거절은 반품 승인 · 거절 API(#17 B2c-2) 뒤에 행이 생긴다.
 */
export const CLAIM_STATUSES = [
  'REQUESTED',
  'APPROVED',
  'REFUNDED',
  'COMPLETED',
  'REJECTED',
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
  APPROVED: '승인',
  REFUNDED: '환불 완료',
  COMPLETED: '완료',
  REJECTED: '거절',
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
  /**
   * 지금 "환불 완료로 확정" 을 할 수 있는가(#17 A3). 보는 사람의 권한과 무관한 클레임 판정이다 — 화면은 역할로 한 번 더 거른다.
   * 주문 상태는 실행 때 다시 보므로 true 여도 409 `ORDER_041` 일 수 있다.
   */
  refundConfirmable: boolean;
  /** 지금 "재고 복원 없이 종결" 을 할 수 있는가(#17 A3). `refundConfirmable` 과 같은 규칙이다 */
  completable: boolean;
  /** 마지막 운영자 결정(확정 · 종결). 없으면 null. 마지막 결정만 남는다(확정 뒤 종결하면 확정 사유는 덮인다, BE #694) */
  lastDecision: ClaimDecision | null;
  /** 화면 버전 — 다시 시도 · 확정 · 종결 본문의 `expectedVersion` 으로 그대로 보낸다(멱등 키 대신 낙관적 버전으로 겹친 요청을 막는다) */
  version: number;
};

/** `ClaimAdminRecoveryItem.DecisionItem` — 결정자 아이디는 내려오지 않는다(감사 로그 · 주문 이력에 남는다) */
export type ClaimDecision = {
  /** 판단 근거 — plain text 라 React 텍스트로만 보인다 */
  reason: string;
  /** 결정 시각(UTC ISO) */
  decidedAt: string;
};

/** 반품 귀책 — 서버 `ReturnRefundPolicy.Fault`. 승인 본문의 `fault` 로 보낸다 */
export const RETURN_FAULTS = ['CUSTOMER', 'SELLER'] as const;
export type ReturnFaultCode = (typeof RETURN_FAULTS)[number];

/** `ClaimAdminReturnDecisionItem.RefundItem` — 귀책 하나의 정책 환불액(차감 전). **0 이하일 수 있다**(그 귀책으로는 승인할 수 없다) */
export type ReturnRefundOption = { fault: CodeName; amount: Money };

/**
 * `ClaimAdminReturnDecisionItem` — 반품 결정 미리보기(#17 B2c). 접수(REQUESTED) 반품에만 있고 그 밖은 null 이다.
 * 잠그지 않은 표시용이라 승인 API 가 같은 판정 · 산식을 다시 한다.
 */
export type ReturnDecision = {
  /** 지금 승인할 수 있는가 — `reason` 이 null 일 때만 true */
  approvable: boolean;
  /** 지금 거절할 수 있는가 — `reason` 이 NOT_DECIDABLE 일 때만 false */
  rejectable: boolean;
  /** 승인할 수 없는 이유 — NOT_DECIDABLE · RETURN_IN_FLIGHT · NOT_CALCULABLE · REFUND_NOT_OPEN. name · description 을 그대로 보인다 */
  reason: CodeName | null;
  /** 항목 사유로 정한 기본 귀책(CUSTOMER · SELLER) — 운영자가 검수 뒤 바꿀 수 있다 */
  defaultFault: CodeName;
  /** 승인할 수 있거나 REFUND_NOT_OPEN(표시용)일 때만 CUSTOMER · SELLER 순으로 있고, 아니면 빈 배열이다 */
  refundByFault: ReturnRefundOption[];
};

/** `ClaimAdminReturnApprovalItem` — 승인된 반품의 승인 기록. 승인자 아이디는 내려오지 않는다. 환불액은 상세의 `expectedRefund` 다 */
export type ReturnApproval = {
  fault: CodeName;
  restock: boolean;
  deduction: Money;
  /** 승인 시각(UTC ISO) */
  approvedAt: string;
};

/** `ClaimAdminResponse` — 항목은 주문 항목 아이디 오름차순. 다시 시도 · 확정 · 종결 · 반품 승인 · 거절 응답도 같은 모양(현재 상세)이다 */
export type ClaimDetail = ClaimListItem & {
  items: ClaimLine[];
  recovery: ClaimRecovery;
  /** 승인된 반품에만 있다(승인 전 · 거절 반품과 취소는 null) */
  returnApproval: ReturnApproval | null;
  /** 접수(REQUESTED) 반품에만 있다 */
  returnDecision: ReturnDecision | null;
};
