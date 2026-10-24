package com.commercefoundry.domainlayer.cart.adapter.in.web.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogErrorCode;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 5xx 로그는 코드와 원인 예외 타입만 남긴다 — 원인 메시지(DB 행 값 · 대상 응답 본문)와 스택트레이스는 남지 않는다 ({@code coding-conventions.md} §8, #277).
 */
@ExtendWith(OutputCaptureExtension.class)
class CartExceptionHandlerLogTest {

    private static final String LOG_TEXT = "cart request failed.";
    private static final String SECRET_DETAIL = "Key (member_id, sku_id)=(secret-row-value) already exists";

    private final CartExceptionHandler handler = new CartExceptionHandler();

    @Test
    @DisplayName("5xx CartException — 코드 · 원인 타입만 남고 원인 메시지 · 스택트레이스는 남지 않는다")
    void cartExceptionLogsCodeAndCauseTypeOnly(CapturedOutput output) {
        CartException exception = new CartException(CartErrorCode.CART_PRODUCT_SERVICE_UNAVAILABLE, new DataIntegrityViolationException(SECRET_DETAIL));

        ResponseEntity<Response<Void>> response = handler.handleCartException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().dataHeader().resultMessage()).as("원인 메시지는 응답에도 실리지 않는다").doesNotContain("secret-row-value");
        assertThat(lines(output)).singleElement().asString()
            .contains("code=" + CartErrorCode.CART_PRODUCT_SERVICE_UNAVAILABLE.getCode())
            .contains("causeType=" + DataIntegrityViolationException.class.getSimpleName());
        assertNoLeak(output);
    }

    @Test
    @DisplayName("CatalogException — 번역한 코드 · catalog 코드 · 원인 타입만 남고 원인 메시지 · 스택트레이스는 남지 않는다")
    void catalogExceptionLogsCodeAndCauseTypeOnly(CapturedOutput output) {
        CatalogException exception = new CatalogException(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE, new IllegalStateException(SECRET_DETAIL));

        ResponseEntity<Response<Void>> response = handler.handleCatalogException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().dataHeader().resultMessage()).as("원인 메시지는 응답에도 실리지 않는다").doesNotContain("secret-row-value");
        assertThat(lines(output)).singleElement().asString()
            .contains("code=" + CartErrorCode.CART_PRODUCT_SERVICE_UNAVAILABLE.getCode())
            .contains("cause=" + CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE.getCode())
            .contains("causeType=" + IllegalStateException.class.getSimpleName());
        assertNoLeak(output);
    }

    @Test
    @DisplayName("4xx CartException 은 로그를 남기지 않는다 — 뒤이은 5xx 한 줄만 캡처된다")
    void clientErrorIsNotLogged(CapturedOutput output) {
        handler.handleCartException(new CartException(CartErrorCode.INVALID_REQUEST));
        // 캡처가 동작하는지 같은 테스트에서 확인한다 — 캡처가 안 되면 "로그 없음" 단언이 거짓 통과한다
        handler.handleCartException(new CartException(CartErrorCode.CART_PRODUCT_SERVICE_UNAVAILABLE));

        assertThat(lines(output)).singleElement().asString()
            .contains("code=" + CartErrorCode.CART_PRODUCT_SERVICE_UNAVAILABLE.getCode())
            .doesNotContain(CartErrorCode.INVALID_REQUEST.getCode());
    }

    private static List<String> lines(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains(LOG_TEXT)).toList();
    }

    private static void assertNoLeak(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain("secret-row-value").doesNotContain("\tat ").doesNotContain("Caused by");
    }
}
