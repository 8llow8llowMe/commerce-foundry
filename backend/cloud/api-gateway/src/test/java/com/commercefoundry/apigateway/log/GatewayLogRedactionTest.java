package com.commercefoundry.apigateway.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 게이트웨이 로그 가림 — common-core {@code RedactedThrowable} · {@code FallbackErrorResolver#maskPath}(#280)와 같은 규칙인지 본다. */
class GatewayLogRedactionTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "/api/v1/orders/4611686018427387904 | /api/v1/orders/…",
        "/api/v1/orders/1/cancel | /api/v1/orders/…",
        "/api/v1/products | /api/v1/products",
        "/api/v1/products/ | /api/v1/products/",
        "/api | /api",
        "/api/v1/admin;jsessionid=x/y | /api/v1/admin;…/…",
        "/api;x=y | /api;…"
    })
    @DisplayName("앞 3구간만 남기고 나머지는 /… — 남기는 구간의 ; 매트릭스 파라미터도 가린다")
    void masksPath(String path, String expected) {
        assertThat(GatewayLogRedaction.maskPath(path)).isEqualTo(expected);
    }

    @Test
    @DisplayName("경로가 없으면 null")
    void nullPath() {
        assertThat(GatewayLogRedaction.maskPath(null)).isNull();
    }

    @Test
    @DisplayName("사본은 메시지 자리에 원래 타입을 두고 원래 스택 프레임을 지닌다 — 원인 · 억제된 예외도 같다")
    void redactsChain() {
        IllegalArgumentException cause = new IllegalArgumentException("body MARKER-CAUSE");
        IllegalStateException failure = new IllegalStateException("header MARKER-TOP", cause);
        failure.addSuppressed(new UnsupportedOperationException("MARKER-SUPPRESSED"));

        Throwable redacted = GatewayLogRedaction.redact(failure);

        assertThat(redacted.getMessage()).isEqualTo("java.lang.IllegalStateException");
        assertThat(redacted.getStackTrace()).isEqualTo(failure.getStackTrace());
        assertThat(redacted.getCause().getMessage()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(redacted.getCause().getStackTrace()).isEqualTo(cause.getStackTrace());
        assertThat(redacted.getSuppressed()).singleElement()
            .satisfies(suppressed -> assertThat(suppressed.getMessage()).isEqualTo("java.lang.UnsupportedOperationException"));
        assertThat(redacted.toString() + redacted.getCause() + redacted.getSuppressed()[0]).doesNotContain("MARKER");
    }

    @Test
    @DisplayName("순환 원인 사슬은 한 번만 따라간다")
    void stopsAtCycle() {
        IllegalStateException first = new IllegalStateException("a");
        IllegalArgumentException second = new IllegalArgumentException("b", first);
        first.initCause(second);

        Throwable redacted = GatewayLogRedaction.redact(first);

        assertThat(redacted.getCause().getMessage()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(redacted.getCause().getCause()).isNull();
    }

    @Test
    @DisplayName("SQLState 는 표준 모양일 때만 싣는다")
    void sqlStateOnlyWhenStandard() {
        assertThat(GatewayLogRedaction.redact(new SQLException("duplicate MARKER", "23505")).getMessage())
            .isEqualTo("java.sql.SQLException [sqlState=23505]");
        assertThat(GatewayLogRedaction.redact(new SQLException("x", "value MARKER")).getMessage()).isEqualTo("java.sql.SQLException");
    }

    @Test
    @DisplayName("null 이면 null")
    void nullThrowable() {
        assertThat(GatewayLogRedaction.redact(null)).isNull();
    }
}
