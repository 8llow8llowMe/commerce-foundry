package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.model.PgApprovalRequest;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;

/**
 * PG 연동 ({@code payment-guide.md} §1). application 은 이 포트만 알고, 토스는 {@code adapter/out/client/pg/toss} 에만 나온다.
 *
 * <p>둘 다 원격 호출이다 — DB 트랜잭션 안에서 부르지 않는다 ({@code payment-guide.md} §3-3). 예외를 던지지 않고 결과로 돌려준다 —
 * 실패를 예외로 흘리면 "모름" 과 "거절" 이 같은 catch 로 섞인다.
 */
public interface PaymentGatewayPort {

    /**
     * 승인을 요청한다. 자동 재시도하지 않는다 — 같은 멱등 키로 다시 부를지는 Processor 가 결제 시도의 상태로 정한다.
     *
     * @return 승인 · 거절 · 모름 · 보내지 않음 중 하나. null 이 아니다
     */
    PgApprovalQueryResult approve(PgApprovalRequest request);

    /**
     * PG 주문번호로 결제를 조회한다. 결제키가 아니라 주문번호로 묻는다 — 주문번호는 우리가 결제 시도마다 만든 값이라, 고객이 보낸 결제키가
     * 다른 결제의 것이어도 이 결제 시도의 결과만 돌아온다.
     *
     * @return 승인 · 실패 · 처리 중 · 없음 · 모름 중 하나. null 이 아니다
     */
    PgInquiryQueryResult inquire(String pgOrderId);
}
