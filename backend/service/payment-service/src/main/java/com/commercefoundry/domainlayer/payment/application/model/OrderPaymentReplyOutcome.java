package com.commercefoundry.domainlayer.payment.application.model;

/** 커밋이 끝난 회신 기록 결과. 취소 실행을 뜻하지 않는다. */
public record OrderPaymentReplyOutcome(boolean duplicate, boolean changed, boolean rejectionRecorded) {
    public static OrderPaymentReplyOutcome alreadyProcessed() {
        return new OrderPaymentReplyOutcome(true, false, false);
    }
}
