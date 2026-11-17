package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가격 정본 모드 (#20 PR-2b, {@code usd-pricing-guide.md} §4). 기본은 LEGACY 다.
 * PRICE_BOOK 은 운영 backfill 이 끝나 사전 점검이 READY · BASE_MISSING 0 인 것을 확인하고 사용자 승인 뒤에만 켠다.
 * 환경 변수 {@code PRICE_SOURCE_MODE} 로 덮어쓴다.
 *
 * @param mode LEGACY(writer 가 빠진 기준통화 행을 legacy 값으로 보완 — 롤백 경로) ·
 *             PRICE_BOOK(가격행이 쓰기 정본 — 빠진 기준통화 행은 보완하지 않고 거절). null 이면 LEGACY
 */
@ConfigurationProperties(prefix = "price-source")
public record PriceSourceProperties(Mode mode) {

    public enum Mode { LEGACY, PRICE_BOOK }

    public PriceSourceProperties {
        mode = mode == null ? Mode.LEGACY : mode;
    }
}
