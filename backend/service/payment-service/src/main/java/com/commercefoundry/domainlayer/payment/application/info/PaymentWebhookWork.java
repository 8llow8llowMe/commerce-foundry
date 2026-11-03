package com.commercefoundry.domainlayer.payment.application.info;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;

/** claim 커밋 당시의 결제 전체 스냅숏. PG 조회 뒤 모든 칸·버전을 비교한다. */
public record PaymentWebhookWork(PaymentWebhookInbox inbox, Payment payment) {
    @Override public String toString() { return "PaymentWebhookWork[inboxId=" + inbox.id() + "]"; }
}
