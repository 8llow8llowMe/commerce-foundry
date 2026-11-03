package com.commercefoundry.domainlayer.payment.adapter.out.client;

import com.commercefoundry.domainlayer.payment.adapter.out.client.feign.OrderPaymentResultClient;
import com.commercefoundry.domainlayer.payment.adapter.out.client.support.OrderPaymentResultResponseSupport;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException.Failure;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentResultQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderPaymentResultClientAdapter implements OrderPaymentResultQueryPort {

    private final OrderPaymentResultClient client;
    private final OrderPaymentResultResponseSupport support;

    @Override
    public OrderPaymentResultQueryResult query(long paymentId) {
        if (paymentId <= 0) {
            throw new OrderPaymentResultQueryException(Failure.CONTRACT_VIOLATION);
        }
        return support.toResult(support.call(() -> client.getResult(paymentId)), paymentId);
    }
}
