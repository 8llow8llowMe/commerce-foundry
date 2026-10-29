package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.domainlayer.order.application.port.out.query.OrderExpiryCandidateQueryResult;
import java.time.Instant;

/**
 * 미결제 만료 스윕 대상 한 건 — Facade 가 건별 만료에 아이디를 쓰고, 배치의 마지막 건을 같은 실행의 다음 배치 커서({@code (paymentDueAt, orderId)} keyset)로
 * Processor 에 돌려준다. out-port 조회 결과({@link OrderExpiryCandidateQueryResult})에서 Processor 가 만든다.
 */
public record OrderExpiryCandidate(long orderId, Instant paymentDueAt) {

    public static OrderExpiryCandidate from(OrderExpiryCandidateQueryResult result) {
        return new OrderExpiryCandidate(result.orderId(), result.paymentDueAt());
    }
}
