package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가격 정본 전환 설정. 바인딩은 LEGACY · PRICE_BOOK 을 모두 받는다(writer 모드 분기를 시험할 수 있게, #20 D6).
 * 운영 기동 거절은 {@link com.commercefoundry.global.config.PriceSourceModeGuard} 가 맡으며, 모든 reader · writer 연결 뒤
 * B7 에서 그 빈만 지워 해제한다. 실제 전환 조건은 usd-pricing-guide.md §4 다.
 *
 * @param mode 가격 정본. null 은 LEGACY
 */
@ConfigurationProperties(prefix = "price-source")
public record PriceSourceProperties(Mode mode) {
    public enum Mode { LEGACY, PRICE_BOOK }

    public PriceSourceProperties {
        mode = mode == null ? Mode.LEGACY : mode;
    }
}
