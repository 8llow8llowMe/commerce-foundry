package com.commercefoundry.domainlayer.notification.adapter.out.client;

import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.PaymentAnomalyObservationClient;
import com.commercefoundry.domainlayer.notification.adapter.out.client.support.PaymentAnomalyObservationInternalResponseSupport;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentAnomalyObservationAdapter implements PaymentAnomalyObservationPort {
    private final PaymentAnomalyObservationClient client;
    private final PaymentAnomalyObservationInternalResponseSupport support;

    @Override
    public PaymentAnomalyObservation observe() {
        return support.observe(client);
    }
}
