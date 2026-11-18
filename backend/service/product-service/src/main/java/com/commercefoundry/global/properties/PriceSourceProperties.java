package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가격 정본 전환의 예약 설정. A에서는 LEGACY만 허용하며 PRICE_BOOK은 바인딩 단계에서 기동을 거절한다.
 * 실제 reader·writer 전환과 운영 승인 조건은 usd-pricing-guide.md §4의 B 후속이다.
 *
 * @param mode 현재 정본 LEGACY. null은 LEGACY이며 PRICE_BOOK은 미연결 예약값이다
 */
@ConfigurationProperties(prefix = "price-source")
public record PriceSourceProperties(Mode mode) {
    public enum Mode { LEGACY, PRICE_BOOK }

    public PriceSourceProperties {
        mode = mode == null ? Mode.LEGACY : mode;
        if (mode != Mode.LEGACY) {
            throw new IllegalArgumentException("가격 정본 전환은 아직 지원하지 않습니다");
        }
    }
}
