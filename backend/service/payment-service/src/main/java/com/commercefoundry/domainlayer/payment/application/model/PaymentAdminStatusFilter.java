package com.commercefoundry.domainlayer.payment.application.model;

/**
 * 어드민 조회 입력. 앞의 10개는 실제 결제 상태이고, 뒤의 둘은 실제 결제 상태가 아닌 취소 원장 이력 필터다.
 *
 * <ul>
 *   <li>{@code REFUND_FAILED} — 취소 확정 거절(REJECTED) 원장이 하나라도 있는 결제</li>
 *   <li>{@code EXTERNAL_REFUNDED} — 외부 확인 환불(EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED, #693) 원장이 하나라도 있는 결제</li>
 * </ul>
 */
public enum PaymentAdminStatusFilter {
    READY, IN_PROGRESS, DONE, FAILED, UNKNOWN, ABORTED, LATE_APPROVED, PARTIAL_CANCELED, CANCELED, WAITING_FOR_DEPOSIT, REFUND_FAILED, EXTERNAL_REFUNDED
}
