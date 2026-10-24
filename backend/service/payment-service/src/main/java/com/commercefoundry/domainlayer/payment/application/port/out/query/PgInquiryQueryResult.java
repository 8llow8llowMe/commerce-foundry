package com.commercefoundry.domainlayer.payment.application.port.out.query;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentInquiry;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import java.util.Objects;

/**
 * PG 결제 조회의 결과 ({@code payment-guide.md} §4). UNKNOWN 재조회 · 되돌린 READY 를 버리기 전 확인에 쓴다.
 *
 * <p>확정한 것({@link Approved} · {@link Failed})만 도메인 {@link PaymentInquiry} 로 바뀐다. PG 가 아직 처리 중({@link Pending}) · 결제가 없음({@link NotFound}) ·
 * 조회 실패({@link Unknown})는 실패로 뭉개지 않는다 — 처리 중인 결제를 실패로 확정하면 그 뒤의 승인이 늦은 승인이 된다.
 *
 * <p>{@link Approved} · {@link Failed} · {@link Pending} 은 PG 가 답한 결제의 결제키를 싣는다 — 어느 결제키에 대한 답인지 도메인이 결제 시도의 결제키와 대조한다.
 * 어댑터는 요청한 결제키 · 주문번호와 응답이 다르면 이 셋이 아니라 {@link Unknown}({@link PaymentUnknownReason#CONTRACT})으로 돌려준다.
 */
public sealed interface PgInquiryQueryResult {

    /** PG 가 승인했다 (PG 상태 DONE). 결제키는 {@link PaymentApproval#paymentKey()} 다. */
    record Approved(PaymentApproval approval) implements PgInquiryQueryResult {

        public Approved {
            Objects.requireNonNull(approval, "approval");
        }

        public String paymentKey() {
            return approval.paymentKey();
        }

        public PaymentInquiry.Approved toInquiry() {
            return new PaymentInquiry.Approved(approval);
        }
    }

    /**
     * PG 가 승인하지 않고 끝냈다 (PG 상태 ABORTED · EXPIRED). 다시 승인될 수 없다.
     *
     * @param paymentKey PG 가 답한 결제의 결제키
     * @param failureCode PG 오류 코드. 응답에 없으면 PG 상태 문자열이다 — {@link Payment#FAILURE_CODE_MAX_LENGTH} 자 이하로 정규화돼 있다
     * @param failureMessage PG 오류 메시지. 없으면 null
     */
    record Failed(String paymentKey, String failureCode, String failureMessage) implements PgInquiryQueryResult {

        public Failed {
            Objects.requireNonNull(paymentKey, "paymentKey");
            Objects.requireNonNull(failureCode, "failureCode");
        }

        public PaymentInquiry.Failed toInquiry() {
            return new PaymentInquiry.Failed(paymentKey, failureCode, failureMessage);
        }

        /** PG 메시지 · 결제키 전체는 로그에 남기지 않는다 — 코드만 찍는다. */
        @Override
        public String toString() {
            return "Failed[failureCode=" + failureCode + "]";
        }
    }

    /**
     * PG 가 아직 끝내지 않았다 (PG 상태 READY · IN_PROGRESS — 인증만 됐거나 승인 처리 중). 지금 상태 그대로 두고 다음 재조회를 기다린다.
     *
     * @param paymentKey PG 가 답한 결제의 결제키
     */
    record Pending(String paymentKey) implements PgInquiryQueryResult {

        public Pending {
            Objects.requireNonNull(paymentKey, "paymentKey");
        }

        /** 결제키 전체를 로그에 남기지 않는다. */
        @Override
        public String toString() {
            return "Pending[]";
        }
    }

    /**
     * PG 에 이 결제가 없다 (404 NOT_FOUND_PAYMENT). <b>미승인의 증거가 아니다</b> — 실패로 확정하거나 결제 시도를 버리는 근거로 쓰지 않는다.
     * 어떻게 다룰지는 Processor 가 결제 시도의 상태로 정한다.
     */
    record NotFound() implements PgInquiryQueryResult {
    }

    /**
     * 조회로 확정하지 못했다. 이유는 진단용이다 — 결제 시도의 미확정 이유는 조회가 바꾸지 않는다.
     *
     * <ul>
     *   <li>{@link PaymentUnknownReason#TIMEOUT} · {@link PaymentUnknownReason#SERVER_ERROR} — 조회가 실패했다(타임아웃 · 5xx · 429 · 연속 요청 제한 · 서킷 · 연결).
     *       다음 재조회에서 다시 묻는다</li>
     *   <li>{@link PaymentUnknownReason#CONTRACT} — 응답이 계약과 다르다(필수 칸 누락 · 해석 불가 · 다른 주문번호 · 다른 결제키 · 키 설정 오류 같은 그 밖의 4xx)</li>
     *   <li>{@link PaymentUnknownReason#MANUAL_REVIEW} — 응답은 읽었지만 이 흐름으로 확정할 수 없는 상태다(승인 뒤 취소됨 · 입금 대기). 사람이 확인한다</li>
     * </ul>
     */
    record Unknown(PaymentUnknownReason reason) implements PgInquiryQueryResult {

        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
