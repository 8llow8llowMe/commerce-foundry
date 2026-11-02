import type { Money } from '@cf/api-client';
import type { CodeName } from '@/lib/product/detail';

/**
 * 어드민 결제 목록 API(#15 일부 · BE PR #484, payment-service `PaymentAdminController`). 읽기 전용이다 —
 * PG 재조회 · 재승인 · 주문 이벤트 재발행을 하지 않는다. 취소 · 환불 · 웹훅 처리는 아직 API 가 없다(#15 잔여).
 * BFF 는 범용 프록시(`app/api/[...path]`)가 그대로 넘긴다.
 */
export const ADMIN_PAYMENTS_PATH = '/api/v1/admin/payments';

/**
 * 서버 `PaymentAdminStatusFilter` — 목록 조건에만 쓴다.
 * `REFUND_FAILED` 는 서버가 아직 받지 않아(400 `PAYMENT_198`) 넣지 않는다.
 */
export const PAYMENT_STATUSES = [
  'READY',
  'IN_PROGRESS',
  'DONE',
  'FAILED',
  'UNKNOWN',
  'ABORTED',
  'LATE_APPROVED',
  'PARTIAL_CANCELED',
  'CANCELED',
  'WAITING_FOR_DEPOSIT',
] as const;

export type PaymentStatusCode = (typeof PAYMENT_STATUSES)[number];

/** 조건 고르기용 이름 — 서버 `PaymentStatus` displayName 과 같다. 응답에 온 상태는 응답의 `name` 을 그대로 보인다 */
export const PAYMENT_STATUS_LABELS: Record<PaymentStatusCode, string> = {
  READY: '결제 대기',
  IN_PROGRESS: '결제 승인 중',
  DONE: '결제 완료',
  FAILED: '결제 실패',
  UNKNOWN: '결제 확인 중',
  ABORTED: '결제 중단',
  LATE_APPROVED: '늦은 승인',
  PARTIAL_CANCELED: '부분 취소',
  CANCELED: '결제 취소',
  WAITING_FOR_DEPOSIT: '입금 대기',
};

/**
 * `PaymentAdminItem`. 결제키 · 멱등 키 · PG 원문 · 회원 정보는 서버가 내려 주지 않는다.
 * 시각은 UTC ISO 다.
 */
export type PaymentListItem = {
  paymentId: string;
  orderId: string;
  status: CodeName;
  /** 저장된 결제 시도 원금 — 환불 차감 잔액 · PG 대사 결과가 아니다 */
  amount: Money;
  /** 승인 전이면 null */
  method: CodeName | null;
  /** 미확정 사유. 해결된(DONE · FAILED) 뒤에도 과거 원인으로 남을 수 있다 */
  unknownReason: CodeName | null;
  /** 주문의 성공 회신을 받았는지. false 자체가 재시도 대상이라는 뜻은 아니다 */
  orderSynced: boolean;
  /** 주문 거절 회신 코드. 아직 회신이 없거나 성공이면 null */
  orderSyncFailureCode: string | null;
  createdAt: string;
  updatedAt: string;
  approvalStartedAt: string | null;
  approvedAt: string | null;
};
