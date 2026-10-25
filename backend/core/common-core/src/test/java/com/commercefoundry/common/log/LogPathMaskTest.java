package com.commercefoundry.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogPathMaskTest {

    @ParameterizedTest
    @CsvSource({
        "/api/v1/orders/4611686018427387904, /api/v1/orders/…",
        "/api/v1/orders/1/cancel, /api/v1/orders/…",
        "/api/v1/orders/1/items/2, /api/v1/orders/…",
        "/internal/v1/orders/4611686018427387904/payment-completed, /internal/v1/orders/…",
        "/api/v1/admin/products/1, /api/v1/admin/…",
        "/api/v1/products, /api/v1/products",
        "/api/v1/products/, /api/v1/products/",
        "/api/v1/orders, /api/v1/orders",
        "/api/v1/orders/, /api/v1/orders/",
        "/internal/v1/__security-smoke, /internal/v1/__security-smoke",
        "/api/v1/smoke, /api/v1/smoke",
        "/fallback-smoke, /fallback-smoke",
        "/api, /api",
        "/, /"
    })
    @DisplayName("앞 3구간만 남긴다 — 경로의 식별자를 남기지 않는다")
    void masksAfterThreeSegments(String path, String expected) {
        assertThat(LogPathMask.mask(path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "/api/v1/admin;jsessionid=x/y, /api/v1/admin;…/…",
        "/api/v1/admin;x=y, /api/v1/admin;…",
        "/api;x=<script>/v1/orders/1, /api;…/v1/orders/…",
        "/api/v1;a=1;b=2/orders, /api/v1;…/orders",
        "/api/v1/orders/..;/admin/x, /api/v1/orders/…",
        "/internal/v1/x;y, /internal/v1/x;…",
        "/api;x=y, /api;…"
    })
    @DisplayName("남기는 구간도 ; 뒤(매트릭스 파라미터)는 ;… 로 가린다")
    void masksMatrixParameters(String path, String expected) {
        assertThat(LogPathMask.mask(path)).isEqualTo(expected);
    }

    @Test
    @DisplayName("경로가 없으면 null")
    void nullPath() {
        assertThat(LogPathMask.mask(null)).isNull();
    }
}
