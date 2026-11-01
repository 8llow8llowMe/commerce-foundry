package com.commercefoundry.domainlayer.payment.adapter.out.client;

import com.commercefoundry.domainlayer.payment.adapter.out.client.feign.OrderPaymentSummaryClient;
import com.commercefoundry.domainlayer.payment.adapter.out.client.support.OrderInternalResponseSupport;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentSummaryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentSummaryQueryResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * order-service 주문 요약 어댑터. 호출은 {@link OrderInternalResponseSupport} 가 서킷으로 감싸고 응답 계약을 본다 — 이 클래스는 호출을 넘기기만 한다.
 */
@Component
@RequiredArgsConstructor
public class OrderPaymentSummaryClientAdapter implements OrderPaymentSummaryPort {

    private final OrderPaymentSummaryClient orderPaymentSummaryClient;
    private final OrderInternalResponseSupport orderInternalResponseSupport;

    @Override
    public Optional<OrderPaymentSummaryQueryResult> find(long orderId, long memberId) {
        return orderInternalResponseSupport.callAllowingOrderNotFound(() -> orderPaymentSummaryClient.getPaymentSummary(orderId, memberId))
            .map(response -> orderInternalResponseSupport.toPaymentSummaryResult(response, orderId, memberId));
    }
}
