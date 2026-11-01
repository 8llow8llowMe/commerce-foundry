package com.commercefoundry.domainlayer.payment.application.info;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import java.time.Instant;
import java.util.Objects;

/**
 * 결제 준비 결과 — 결제 시도와 주문의 결제 기한. 기한은 결제 시도가 아니라 주문의 값이라 Info 로 묶는다.
 *
 * @param payment 새로 만들었거나 이미 있던 활성 결제 시도
 * @param paymentDueAt 주문의 결제 기한 (UTC)
 */
public record PaymentPrepareInfo(Payment payment, Instant paymentDueAt) {

    public PaymentPrepareInfo {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(paymentDueAt, "paymentDueAt");
    }
}
