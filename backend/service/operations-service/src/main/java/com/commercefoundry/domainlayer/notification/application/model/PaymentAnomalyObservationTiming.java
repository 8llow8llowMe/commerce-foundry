package com.commercefoundry.domainlayer.notification.application.model;

import java.time.Instant;
import java.util.Objects;

/** 같은 JVM에서 원천 HTTP 시작부터 잠금 후 판정까지 측정한 불변 값이다. */
public record PaymentAnomalyObservationTiming(Instant beforeHttp, Instant afterHttp, long startedNanos) {
    public PaymentAnomalyObservationTiming {
        Objects.requireNonNull(beforeHttp, "beforeHttp");
        Objects.requireNonNull(afterHttp, "afterHttp");
    }
}
