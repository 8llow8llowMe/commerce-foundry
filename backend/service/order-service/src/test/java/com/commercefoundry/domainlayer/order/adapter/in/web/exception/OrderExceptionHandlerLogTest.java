package com.commercefoundry.domainlayer.order.adapter.in.web.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.catalog.application.exception.CatalogErrorCode;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogException;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * 5xx 로그는 코드와 원인 예외 타입만 남긴다 — 원인 메시지(DB 행 값 · 대상 응답 본문)와 스택트레이스는 남지 않는다 ({@code coding-conventions.md} §8, #277).
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderExceptionHandlerLogTest {

    private static final String LOG_TEXT = "order request failed.";
    private static final String SECRET_DETAIL = "Key (member_id, idempotency_key)=(secret-row-value) already exists";

    private final OrderExceptionHandler handler = new OrderExceptionHandler();

    @Test
    @DisplayName("5xx OrderException — 코드 · 원인 타입만 남고 원인 메시지 · 스택트레이스는 남지 않는다")
    void orderExceptionLogsCodeAndCauseTypeOnly(CapturedOutput output) {
        OrderException exception = new OrderException(OrderErrorCode.ORDER_SAVE_FAILED, new DataIntegrityViolationException(SECRET_DETAIL));

        assertThat(handler.handleOrderException(exception).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(lines(output)).singleElement().asString()
            .contains("code=" + OrderErrorCode.ORDER_SAVE_FAILED.getCode())
            .contains("causeType=" + DataIntegrityViolationException.class.getSimpleName());
        assertNoLeak(output);
    }

    @Test
    @DisplayName("CatalogException — 번역한 코드 · catalog 코드 · 원인 타입만 남고 원인 메시지 · 스택트레이스는 남지 않는다")
    void catalogExceptionLogsCodeAndCauseTypeOnly(CapturedOutput output) {
        CatalogException exception = new CatalogException(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE, new IllegalStateException(SECRET_DETAIL));

        assertThat(handler.handleCatalogException(exception).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(lines(output)).singleElement().asString()
            .contains("code=" + OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE.getCode())
            .contains("cause=" + CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE.getCode())
            .contains("causeType=" + IllegalStateException.class.getSimpleName());
        assertNoLeak(output);
    }

    private static List<String> lines(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains(LOG_TEXT)).toList();
    }

    private static void assertNoLeak(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain("secret-row-value").doesNotContain("\tat ").doesNotContain("Caused by");
    }
}
