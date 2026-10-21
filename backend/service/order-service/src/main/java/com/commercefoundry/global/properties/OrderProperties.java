package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 주문 설정. 기본값은 {@code application.yml} 에 둔다 — 값이 빠지거나 0 · 음수면 기동하지 않는다.
 *
 * @param creation 주문 생성
 */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(Creation creation) {

    public OrderProperties {
        Objects.requireNonNull(creation, "order.creation 설정이 없습니다.");
    }

    /**
     * @param processingLease 주문 요청 기록의 처리 중 lease — 그 안의 같은 키 재요청은 409, 지나면 이어받는다. 원격 호출(배송비 · 재고 선점) 타임아웃 합보다 넉넉해야 한다
     */
    public record Creation(Duration processingLease) {

        public Creation {
            Objects.requireNonNull(processingLease, "order.creation.processing-lease 설정이 없습니다.");
            if (processingLease.isZero() || processingLease.isNegative()) {
                throw new IllegalArgumentException("order.creation.processing-lease 는 0 보다 커야 합니다: " + processingLease);
            }
        }
    }
}
