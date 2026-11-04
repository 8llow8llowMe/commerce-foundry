package com.commercefoundry.domainlayer.notification.adapter.out.client.support;

import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.PaymentAnomalyObservationClient;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class PaymentAnomalyObservationInternalResponseSupport {
    private final CircuitBreakerRegistry registry;

    public PaymentAnomalyObservation observe(PaymentAnomalyObservationClient client) {
        try {
            return registry.circuitBreaker("payment-service").executeSupplier(() -> normalize(client.anomalies()));
        } catch (RuntimeException ignored) {
            // Do not retain remote exceptions: their messages may contain body, URL, or credentials.
            throw new NotificationException(NotificationErrorCode.PAYMENT_ANOMALY_OBSERVATION_UNAVAILABLE);
        }
    }

    private PaymentAnomalyObservation normalize(JsonNode root) {
        object(root);
        var header = root.path("dataHeader");
        object(header);
        var success = header.path("success");
        if (!success.isBoolean() || !success.booleanValue()) {
            throw new IllegalArgumentException("Invalid anomaly envelope");
        }
        var body = root.path("dataBody");
        object(body);
        return new PaymentAnomalyObservation(instant(body.path("observedAt")), fact(body.path("unknown")), fact(body.path("manualReviewUnknown")),
            fact(body.path("inProgress")), fact(body.path("returnedReady")), fact(body.path("lateApproved")), fact(body.path("doneOrderUnsynced")));
    }

    private PaymentAnomalyObservation.Fact fact(JsonNode node) {
        object(node);
        var count = node.path("count");
        var oldest = node.path("oldestAt");
        if (!count.isIntegralNumber() || !count.canConvertToLong() || oldest.isMissingNode()) {
            throw new IllegalArgumentException("Invalid anomaly fact");
        }
        return new PaymentAnomalyObservation.Fact(count.longValue(), oldest.isNull() ? null : instant(oldest));
    }

    private Instant instant(JsonNode node) {
        if (!node.isString()) {
            throw new IllegalArgumentException("Invalid anomaly timestamp");
        }
        var value = Instant.parse(node.stringValue());
        if (value.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Invalid anomaly precision");
        }
        return value;
    }

    private void object(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Invalid anomaly object");
        }
    }
}
