package com.commercefoundry.domainlayer.notification.adapter.out.client.support;

import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.PaymentOutboxObservationClient;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.port.out.query.PaymentOutboxObservationQueryResult;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class PaymentOutboxObservationInternalResponseSupport {
    private final CircuitBreakerRegistry registry;

    public PaymentOutboxObservationQueryResult observe(PaymentOutboxObservationClient client) {
        try {
            return registry.circuitBreaker("payment-service").executeSupplier(() -> normalize(client.ready()));
        } catch (RuntimeException ignored) {
            throw new NotificationException(NotificationErrorCode.OUTBOX_OBSERVATION_UNAVAILABLE);
        }
    }
    private PaymentOutboxObservationQueryResult normalize(JsonNode root) {
        if (root == null || !root.path("dataHeader").path("success").isBoolean() || !root.path("dataHeader").path("success").booleanValue()) {
            throw new IllegalArgumentException("Invalid observation envelope");
        }
        var body = root.path("dataBody");
        var count = body.path("readyUnpublishedCount");
        var oldest = body.path("oldestReadyAvailableAt");
        if (!count.isIntegralNumber() || !count.canConvertToLong() || oldest.isMissingNode()) {
            throw new IllegalArgumentException("Invalid observation body");
        }
        return new PaymentOutboxObservationQueryResult(instant(body.path("observedAt")), count.longValue(), oldest.isNull() ? null : instant(oldest));
    }
    private Instant instant(JsonNode node) {
        if (!node.isString()) {
            throw new IllegalArgumentException("Invalid timestamp");
        }
        var instant = Instant.parse(node.stringValue());
        if (instant.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Invalid timestamp precision");
        }
        return instant;
    }
}
