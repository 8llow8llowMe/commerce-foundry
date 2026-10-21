package com.commercefoundry.domainlayer.payment.support;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/**
 * 결제 시도 테스트 데이터. 값은 전부 테스트 전용이다 — 결제키는 실제 형식을 흉내 낸 가짜 문자열이다.
 */
public final class PaymentFixtures {

    /** 아이디는 6자리 이상이어야 한다 — PG 주문번호(아이디 문자열)의 최소 길이. */
    public static final long PAYMENT_ID = 9_000_000_001L;
    public static final long ORDER_ID = 8_000_000_001L;
    public static final long MEMBER_ID = 7_000_000_001L;
    public static final String PAYMENT_KEY = "tgen_20261021000000abcdEFGH";
    public static final String ORDER_NAME = "생활한복 저고리 외 2건";
    public static final Instant STARTED_AT = Instant.parse("2026-10-21T01:00:00Z");
    public static final Instant APPROVED_AT = Instant.parse("2026-10-21T01:00:03Z");

    private PaymentFixtures() {
    }

    public static Money krw(long amount) {
        return Money.of(amount, CurrencyCode.KRW);
    }

    public static Payment ready() {
        return ready(PAYMENT_ID, ORDER_ID);
    }

    public static Payment ready(long paymentId, long orderId) {
        return Payment.ready(paymentId, orderId, MEMBER_ID, krw(129_000), ORDER_NAME);
    }

    public static Payment inProgress() {
        return ready().startApproval(PAYMENT_KEY, STARTED_AT);
    }

    public static Payment done() {
        return inProgress().approve(approval(krw(129_000)));
    }

    public static Payment failed() {
        return inProgress().reject("REJECT_CARD_COMPANY", "카드사에서 거절했습니다.");
    }

    public static Payment unknown() {
        return inProgress().markUnknown(PaymentUnknownReason.TIMEOUT);
    }

    public static Payment aborted() {
        return ready().abort(PaymentAbortReason.REPREPARED);
    }

    public static PaymentApproval approval(Money amount) {
        return approval(PAYMENT_KEY, amount);
    }

    public static PaymentApproval approval(String paymentKey, Money amount) {
        return new PaymentApproval(paymentKey, PaymentMethod.EASY_PAY, "카카오페이", APPROVED_AT, "DONE", amount);
    }
}
