package com.commercefoundry.domainlayer.payment.application.model;

/**
 * 회신 기록 결과.
 *
 * @param cancelRequested 이번 처리로 거절을 새로 기록했고 아직 돈이 남아 있다(전액 취소가 아니다) — 취소 정책 훅을 부른다
 * @param rejectionReason 거절 사유 코드. 훅을 부르지 않으면 null
 */
public record OrderPaymentReplyOutcome(long paymentId, long orderId, boolean cancelRequested, String rejectionReason) {

    public static OrderPaymentReplyOutcome noCancel(long paymentId, long orderId) {
        return new OrderPaymentReplyOutcome(paymentId, orderId, false, null);
    }
}
