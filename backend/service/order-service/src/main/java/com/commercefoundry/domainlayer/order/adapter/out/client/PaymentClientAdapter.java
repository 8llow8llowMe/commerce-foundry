package com.commercefoundry.domainlayer.order.adapter.out.client;

import com.commercefoundry.domainlayer.order.adapter.out.client.feign.PaymentClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.PaymentInternalResponseSupport;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentClientAdapter implements PaymentQueryPort {

    private final PaymentClient paymentClient;
    private final PaymentInternalResponseSupport paymentInternalResponseSupport;

    @Override
    public Optional<PaymentQueryResult> findPayment(long paymentId) {
        if (paymentId <= 0) {
            throw new IllegalArgumentException("positive paymentId required");
        }
        return paymentInternalResponseSupport.find(paymentId, () -> paymentClient.getPayment(paymentId));
    }
}
