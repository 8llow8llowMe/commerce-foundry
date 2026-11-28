package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.PriceSourceProperties;

/**
 * PRICE_BOOK 기동 거절(#20 D6). 일부 reader · writer 만 가격행을 정본으로 쓰는 상태로 운영하지 않도록, 모든 연결(B3~B6)이
 * 끝날 때까지 컨텍스트 초기화 중(ApplicationRunner 이전)에 기동을 멈춘다. B7 에서 이 빈을 지우는 것으로 해제한다.
 */
public class PriceSourceModeGuard {
    static final String REJECTED = "price-source.mode=PRICE_BOOK 는 아직 지원하지 않습니다";

    public PriceSourceModeGuard(PriceSourceProperties properties) {
        if (properties.mode() != PriceSourceProperties.Mode.LEGACY) {
            throw new IllegalArgumentException(REJECTED);
        }
    }
}
