package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.model.PgApprovalRequest;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;

/**
 * PG 연동 ({@code payment-guide.md} §1). application 은 이 포트만 알고, 토스는 {@code adapter/out/client/pg/toss} 에만 나온다.
 *
 * <p>모두 원격 호출이다 — DB 트랜잭션 안에서 부르지 않는다 ({@code payment-guide.md} §3-3). 예외를 던지지 않고 결과로 돌려준다 —
 * 실패를 예외로 흘리면 "모름" 과 "거절" 이 같은 catch 로 섞인다.
 *
 * <p>조회 결과 중 {@link PgInquiryQueryResult.NotFound} 는 <b>미승인의 증거가 아니다</b> — 결제 시도를 실패로 확정하거나 버리는 근거로 쓰지 않는다.
 * 미승인을 확인하려면 결제키 조회({@link #inquireByPaymentKey})의 {@link PgInquiryQueryResult.Failed} 가 이 결제 시도의 결제키에 대한 답이어야 한다.
 */
public interface PaymentGatewayPort {

    /**
     * 승인을 요청한다. 자동 재시도하지 않는다 — 같은 멱등 키로 다시 부를지는 Processor 가 결제 시도의 상태로 정한다.
     *
     * @return 승인 · 거절 · 모름 · 보내지 않음 중 하나. null 이 아니다
     */
    PgApprovalQueryResult approve(PgApprovalRequest request);

    /**
     * PG 주문번호로 결제를 조회한다. 주문번호는 우리가 결제 시도마다 만든 값이라 이 결제 시도의 결과만 돌아온다. 응답의 주문번호가 다르면 Unknown(CONTRACT) 이다.
     * 응답의 결제키는 결제 시도의 결제키와 다를 수 있다 — 대조는 도메인이 한다.
     *
     * @return 승인 · 실패 · 처리 중 · 없음 · 모름 중 하나. null 이 아니다
     */
    PgInquiryQueryResult inquire(String pgOrderId);

    /**
     * 결제키로 결제를 조회한다 — "이 결제키가 승인되지 않았다" 를 확인할 때(되돌린 READY 를 버리기 전 · UNKNOWN 재조회) 쓴다.
     * 응답의 결제키 · 주문번호가 요청과 다르면 Unknown(CONTRACT) 이다 — 다른 결제의 답으로 이 결제 시도를 확정하지 않는다.
     *
     * @param paymentKey 결제 시도의 결제키
     * @param pgOrderId 결제 시도의 PG 주문번호 — 응답과 대조하고 로그에 남긴다
     * @return 승인 · 실패 · 처리 중 · 없음 · 모름 중 하나. null 이 아니다
     */
    PgInquiryQueryResult inquireByPaymentKey(String paymentKey, String pgOrderId);
}
