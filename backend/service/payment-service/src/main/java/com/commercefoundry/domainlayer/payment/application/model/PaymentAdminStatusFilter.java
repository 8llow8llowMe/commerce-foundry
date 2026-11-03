package com.commercefoundry.domainlayer.payment.application.model;

/** 어드민 조회 입력. REFUND_FAILED는 실제 결제 상태가 아닌 취소 확정 거절 이력 필터다. */
public enum PaymentAdminStatusFilter {
    READY, IN_PROGRESS, DONE, FAILED, UNKNOWN, ABORTED, LATE_APPROVED, PARTIAL_CANCELED, CANCELED, WAITING_FOR_DEPOSIT, REFUND_FAILED
}
