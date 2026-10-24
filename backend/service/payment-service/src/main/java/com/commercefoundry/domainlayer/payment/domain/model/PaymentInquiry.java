package com.commercefoundry.domainlayer.payment.domain.model;

import java.util.Objects;

/**
 * 결제 시도를 PG 에 다시 조회해 확정한 결과 ({@code payment-guide.md} §4). PG 어댑터가 만들고 {@link Payment#resolve} · {@link Payment#abortAfterInquiry} 가 받는다.
 *
 * <p>PG 도 아직 처리 중이라고 답하거나 조회 자체가 실패하면 이 값을 만들지 않는다 — 확정할 수 없으니 지금 상태 그대로 두고 다음 재조회를 기다린다.
 */
public sealed interface PaymentInquiry {

    /** PG 가 승인했다. 승인 응답과 같은 칸으로 기록한다. */
    record Approved(PaymentApproval approval) implements PaymentInquiry {

        public Approved {
            Objects.requireNonNull(approval, "approval");
        }
    }

    /**
     * PG 가 승인하지 않았다(거절 · 인증 만료 등). <b>어느 결제키</b>가 승인되지 않았는지를 함께 싣는다 — 결제 시도의 결제키와 다른 결제의 실패로는
     * 이 결제 시도를 확정하지 않는다({@link Payment#abortAfterInquiry} · {@link Payment#resolve} 가 대조한다).
     *
     * @param paymentKey PG 가 승인하지 않았다고 답한 결제의 결제키
     * @param failureCode PG 오류 코드
     * @param failureMessage PG 오류 메시지. 없으면 null
     */
    record Failed(String paymentKey, String failureCode, String failureMessage) implements PaymentInquiry {

        public Failed {
            Objects.requireNonNull(paymentKey, "paymentKey");
            Objects.requireNonNull(failureCode, "failureCode");
        }

        /** PG 메시지는 결제수단 · 고객 상황을 담을 수 있어 로그에 남기지 않는다 — 코드만 찍는다. 결제키도 앞 8자만 남긴다. */
        @Override
        public String toString() {
            return "Failed[paymentKey=" + Payment.maskPaymentKey(paymentKey) + ", failureCode=" + failureCode + "]";
        }
    }
}
