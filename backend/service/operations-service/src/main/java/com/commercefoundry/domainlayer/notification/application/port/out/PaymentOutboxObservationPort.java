package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.application.port.out.query.PaymentOutboxObservationQueryResult;

public interface PaymentOutboxObservationPort {
    PaymentOutboxObservationQueryResult observe();
}
