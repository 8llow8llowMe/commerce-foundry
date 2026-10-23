package com.commercefoundry.domainlayer.payment.application.port.out.query;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import java.util.Objects;

/**
 * PG 승인 요청의 결과 ({@code payment-guide.md} §4). PG 어댑터가 응답 · 예외를 이 넷 중 하나로 가르고, Processor 가 결제 시도의 전이를 고른다.
 * PG 원본 응답 · Feign 예외는 이 밖으로 나오지 않는다.
 *
 * <table>
 *   <caption>결과와 Processor 의 전이</caption>
 *   <tr><th>결과</th><th>뜻</th><th>전이</th></tr>
 *   <tr><td>{@link Approved}</td><td>PG 가 승인했다고 답했다</td><td>{@link Payment#approve} (금액 · 결제키가 어긋나면 도메인이 거절한다)</td></tr>
 *   <tr><td>{@link Rejected}</td><td>PG 가 명확히 거절했다</td><td>{@link Payment#reject}</td></tr>
 *   <tr><td>{@link Unknown}</td><td>승인됐는지 모른다</td><td>{@link Payment#markUnknown}</td></tr>
 *   <tr><td>{@link NotSent}</td><td>승인이 일어나지 않은 것이 확실하다</td><td>{@link Payment#revertToReady}</td></tr>
 * </table>
 */
public sealed interface PgApprovalQueryResult {

    /** PG 가 승인했다. 금액 · 결제키가 결제 시도와 맞는지는 {@link Payment#approve} 가 본다 — 어댑터는 응답을 그대로 옮긴다. */
    record Approved(PaymentApproval approval) implements PgApprovalQueryResult {

        public Approved {
            Objects.requireNonNull(approval, "approval");
        }
    }

    /**
     * PG 가 승인하지 않았다(카드 거절 · 한도 초과 · 인증 만료 등). 고객은 새 결제 시도로 다시 결제할 수 있다.
     *
     * @param failureCode PG 오류 코드 — {@link Payment#FAILURE_CODE_MAX_LENGTH} 자 이하로 정규화돼 있다
     * @param failureMessage PG 오류 메시지. 없으면 null. 길이는 도메인이 자른다
     */
    record Rejected(String failureCode, String failureMessage) implements PgApprovalQueryResult {

        public Rejected {
            Objects.requireNonNull(failureCode, "failureCode");
        }

        /** PG 메시지는 결제수단 · 고객 상황을 담을 수 있어 로그에 남기지 않는다 — 코드만 찍는다. */
        @Override
        public String toString() {
            return "Rejected[failureCode=" + failureCode + "]";
        }
    }

    /** 승인 요청이 PG 에 닿았을 수 있는데 결과를 모른다 — 타임아웃 · 5xx · 이미 처리됨 · 응답 계약 위반. 재조회로 확정한다. */
    record Unknown(PaymentUnknownReason reason) implements PgApprovalQueryResult {

        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * 승인이 일어나지 않은 것이 확실하다 — 요청이 소켓을 나가지 않았거나(서킷 오픈 · 연결 실패), PG 가 인증 단계에서 요청을 받지 않았다(키 설정 오류).
     * 보냈는지 조금이라도 모르면 이 값이 아니라 {@link Unknown} 이다.
     */
    record NotSent(NotSentReason reason) implements PgApprovalQueryResult {

        public NotSent {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 승인 요청이 승인으로 이어지지 않았음이 확실한 이유. */
    enum NotSentReason {

        /** 서킷이 열려 호출 전에 막혔다 */
        CIRCUIT_OPEN,
        /** 연결을 맺지 못했다(연결 거부 · 호스트 이름 해석 실패) — 요청 본문을 보내기 전이다 */
        CONNECT_FAILED,
        /** PG 가 시크릿 키 · 인증 헤더를 거부했다 — 결제를 처리하기 전 단계다. 재시도로 풀리지 않고 운영자가 키를 고쳐야 한다 */
        CONFIG_ERROR
    }
}
