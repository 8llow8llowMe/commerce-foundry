package com.commercefoundry.domainlayer.payment.domain.model;

import java.util.Objects;

/**
 * 결과 미확정({@link PaymentStatus#UNKNOWN}) 결제를 PG 에 다시 조회해 확정한 결과 ({@code payment-guide.md} §4). PG 어댑터가 만들고 {@link Payment#resolve} 가 받는다.
 *
 * <p>PG 도 아직 처리 중이라고 답하거나 조회 자체가 실패하면 이 값을 만들지 않는다 — 확정할 수 없으니 UNKNOWN 그대로 두고 다음 재조회를 기다린다.
 */
public sealed interface PaymentInquiry {

    /** PG 가 승인했다. 승인 응답과 같은 칸으로 기록한다. */
    record Approved(PaymentApproval approval) implements PaymentInquiry {

        public Approved {
            Objects.requireNonNull(approval, "approval");
        }
    }

    /**
     * PG 가 승인하지 않았다(거절 · 인증 만료 등).
     *
     * @param failureCode PG 오류 코드
     * @param failureMessage PG 오류 메시지. 없으면 null
     */
    record Failed(String failureCode, String failureMessage) implements PaymentInquiry {

        public Failed {
            Objects.requireNonNull(failureCode, "failureCode");
        }
    }
}
