package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 주문 요청 처리 기한. 중복 처리 완화용이며 원격 작업의 소유권을 보장하지 않는다. */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(@DefaultValue Creation creation) {
    public OrderProperties {
        java.util.Objects.requireNonNull(creation, "order.creation is required");
    }
    public record Creation(@DefaultValue("30s") Duration processingLease) {
        public Creation {
            if (processingLease == null || processingLease.compareTo(Duration.ofSeconds(1)) < 0
                || processingLease.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("order.creation.processing-lease must be between 1 second and 5 minutes");
            }
        }
    }
}
