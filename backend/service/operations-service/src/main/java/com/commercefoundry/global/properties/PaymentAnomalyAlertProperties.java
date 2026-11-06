package com.commercefoundry.global.properties;

import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicySettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 정책 기간의 범위는 기존 불변 정책 모델 한 곳에서 검증한다. */
@ConfigurationProperties("operations.notifications.payment-anomaly-alert")
public record PaymentAnomalyAlertProperties(boolean enabled, Duration unknown, Duration manualReviewUnknown, Duration inProgress,
                                            Duration returnedReady, Duration lateApproved, Duration doneOrderUnsynced, Duration suppression,
                                            Duration maxObservationAge, Duration initialDelay, Duration fixedDelay, Duration lockAtMost) {
    public PaymentAnomalyAlertProperties {
        var defaults = PaymentAnomalyPolicySettings.defaults();
        unknown = fallback(unknown, defaults.unknown());
        manualReviewUnknown = fallback(manualReviewUnknown, defaults.manualReviewUnknown());
        inProgress = fallback(inProgress, defaults.inProgress());
        returnedReady = fallback(returnedReady, defaults.returnedReady());
        lateApproved = fallback(lateApproved, defaults.lateApproved());
        doneOrderUnsynced = fallback(doneOrderUnsynced, defaults.doneOrderUnsynced());
        suppression = fallback(suppression, defaults.suppression());
        maxObservationAge = fallback(maxObservationAge, defaults.maxObservationAge());
        new PaymentAnomalyPolicySettings(unknown, manualReviewUnknown, inProgress, returnedReady, lateApproved, doneOrderUnsynced, suppression, maxObservationAge);
        initialDelay = bounded(initialDelay, Duration.ofMinutes(1), Duration.ofDays(1));
        fixedDelay = bounded(fixedDelay, Duration.ofMinutes(1), Duration.ofHours(1));
        lockAtMost = bounded(lockAtMost, Duration.ofMinutes(2), Duration.ofMinutes(10));
    }

    public PaymentAnomalyPolicySettings settings() {
        return new PaymentAnomalyPolicySettings(unknown, manualReviewUnknown, inProgress, returnedReady, lateApproved, doneOrderUnsynced, suppression, maxObservationAge);
    }

    private static Duration fallback(Duration value, Duration defaultValue) { return value == null ? defaultValue : value; }

    private static Duration bounded(Duration value, Duration defaultValue, Duration maximum) {
        value = fallback(value, defaultValue);
        if (value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("결제 이상 수집 기간이 허용 범위를 벗어났습니다.");
        }
        return value;
    }
}
