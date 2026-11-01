package com.commercefoundry.domainlayer.payment.application.model;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import java.util.Objects;

/**
 * PG 승인 요청의 결과를 Facade 로 넘기는 값 ({@code PaymentGatewayProcessor} → Facade → {@code PaymentProcessor#recordApprovalResult}).
 * 포트의 {@code PgApprovalQueryResult} 를 그대로 옮긴다 — QueryResult 는 Facade 로 번지지 않는다({@code architecture-guide.md} §3 H11).
 */
public sealed interface PgApprovalOutcome {

    /** PG 가 승인했다. 금액 · 결제키 대조는 도메인({@code Payment#approve})이 한다. */
    record Approved(PaymentApproval approval) implements PgApprovalOutcome {

        public Approved {
            Objects.requireNonNull(approval, "approval");
        }
    }

    /** PG 가 명확히 거절했다. */
    record Rejected(String failureCode, String failureMessage) implements PgApprovalOutcome {

        public Rejected {
            Objects.requireNonNull(failureCode, "failureCode");
        }

        /** PG 메시지는 결제수단 · 고객 상황을 담을 수 있어 로그에 남기지 않는다 — 코드만 찍는다. */
        @Override
        public String toString() {
            return "Rejected[failureCode=" + failureCode + "]";
        }
    }

    /** 승인됐는지 모른다 — 재조회로 확정한다. */
    record Unknown(PaymentUnknownReason reason) implements PgApprovalOutcome {

        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * 승인이 일어나지 않은 것이 확실하다(서킷 오픈 · 연결 실패 · 키 설정 오류) — 결제 시도를 READY 로 되돌린다.
     *
     * @param reason 보내지 못한 이유 이름 — 로그용
     */
    record NotSent(String reason) implements PgApprovalOutcome {

        public NotSent {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
