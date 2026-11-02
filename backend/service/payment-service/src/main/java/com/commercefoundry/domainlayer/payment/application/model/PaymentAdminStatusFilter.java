package com.commercefoundry.domainlayer.payment.application.model;

/** 어드민 조회 입력. 도메인 상태를 Controller 에 노출하지 않는다. */
public enum PaymentAdminStatusFilter {
    READY, IN_PROGRESS, DONE, FAILED, UNKNOWN, ABORTED, LATE_APPROVED, PARTIAL_CANCELED, CANCELED, WAITING_FOR_DEPOSIT
}
