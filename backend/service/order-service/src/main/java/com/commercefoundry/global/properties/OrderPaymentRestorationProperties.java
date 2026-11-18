package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 최초 복원 작업의 다음 시각에만 반영한다. 스윕 실행·게이트 설정은 후속이다. */
@ConfigurationProperties("order.payment-restoration")
public record OrderPaymentRestorationProperties(@DefaultValue("5m") Duration minAge) {
    public OrderPaymentRestorationProperties {
        Objects.requireNonNull(minAge, "order.payment-restoration.min-age");
        if (minAge.isNegative() || minAge.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("order.payment-restoration.min-age must be between 0 and 1d");
        }
    }
}
