package com.commercefoundry.domainlayer.notification.adapter.out.client;

import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.PaymentOutboxObservationClient;
import com.commercefoundry.domainlayer.notification.adapter.out.client.support.PaymentOutboxObservationInternalResponseSupport;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentOutboxObservationPort;
import com.commercefoundry.domainlayer.notification.application.port.out.query.PaymentOutboxObservationQueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentOutboxObservationAdapter implements PaymentOutboxObservationPort {
    private final PaymentOutboxObservationClient client;
    private final PaymentOutboxObservationInternalResponseSupport support;
    public PaymentOutboxObservationQueryResult observe() {
        return support.observe(client);
    }
}
