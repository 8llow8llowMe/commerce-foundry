package com.commercefoundry.domainlayer.payment.adapter.in.web.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.request.MoneyRequest;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.request.PaymentConfirmRequest;
import com.commercefoundry.domainlayer.payment.application.command.PaymentConfirmCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentRetryLaterException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 결제 핸들러 로그는 코드 · 원인 예외 타입만 남긴다 — 원인 메시지(DB 행 값 · 대상 응답 본문) · 스택 · 결제키가 로그에도 응답에도 없다
 * ({@code coding-conventions.md} §8, #277).
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentExceptionHandlerLogTest {

    private static final String SECRET_DETAIL = "Key (payment_key)=(tgen_secretPaymentKey0001) already exists";
    private static final String PAYMENT_KEY = "tgen_secretPaymentKey0001";

    private final PaymentExceptionHandler handler = new PaymentExceptionHandler();

    @Test
    @DisplayName("5xx PaymentException — 코드 · 원인 타입만 남고 원인 메시지 · 결제키 · 스택은 남지 않는다")
    void serverErrorLogsCodeAndCauseTypeOnly(CapturedOutput output) {
        PaymentException exception = new PaymentException(PaymentErrorCode.APPROVAL_RECORD_FAILED, new DataIntegrityViolationException(SECRET_DETAIL));

        ResponseEntity<Response<Void>> response = handler.handlePaymentException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().dataHeader().resultMessage()).doesNotContain(PAYMENT_KEY);
        assertThat(lines(output, "payment request failed.")).singleElement().asString()
            .contains("code=PAYMENT_013")
            .contains("causeType=" + DataIntegrityViolationException.class.getSimpleName());
        assertNoLeak(output);
    }

    @Test
    @DisplayName("Retry-After 오류 — 코드 · 초만 남기고 헤더는 초 단위다")
    void retryLater(CapturedOutput output) {
        ResponseEntity<Response<Void>> response = handler.handleRetryLater(
            new PaymentRetryLaterException(PaymentErrorCode.GATEWAY_UNAVAILABLE, Duration.ofMillis(4_001)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(output.getAll()).contains("code=PAYMENT_010").contains("retryAfterSeconds=5");
        assertNoLeak(output);
    }

    @Test
    @DisplayName("번역되지 않은 도메인 예외 — 409 PAYMENT_012, 로그는 예외 타입만이고 예외 메시지(상태 · 아이디)는 남지 않는다")
    void untranslatedDomainExceptions(CapturedOutput output) {
        for (RuntimeException exception : List.of(
            new PaymentTransitionException(PaymentStatus.DONE, PaymentTransitionException.Action.APPROVE),
            new PaymentApprovalMismatchException(9_000_000_001L, PaymentApprovalMismatchException.Field.PAYMENT_KEY),
            new PaymentPersistenceConflictException(PaymentPersistenceConflictException.Kind.VERSION, 9_000_000_001L, 8_000_000_001L))) {

            ResponseEntity<Response<Void>> response = handler.handleUntranslated(exception);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().dataHeader().resultCode()).isEqualTo("PAYMENT_012");
            assertThat(response.getBody().dataHeader().resultMessage()).isEqualTo(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT.getMessage());
        }
        assertThat(lines(output, "untranslated domain exception")).hasSize(3);
        assertThat(output.getAll()).doesNotContain("9000000001").doesNotContain("status=DONE");
        assertNoLeak(output);
    }

    @Test
    @DisplayName("승인 요청 DTO · 명령의 toString 은 결제키 앞 8자만 남긴다 — 실수로 로그에 찍혀도 원문이 없다")
    void requestAndCommandMaskPaymentKey() {
        assertThat(new PaymentConfirmCommand(PAYMENT_KEY, "4611686018427387906", 129_000, "KRW").toString())
            .contains("tgen_sec***").doesNotContain(PAYMENT_KEY);
        assertThat(new PaymentConfirmRequest(PAYMENT_KEY, "4611686018427387906", new MoneyRequest(129_000L, "KRW")).toString())
            .contains("tgen_sec***").doesNotContain(PAYMENT_KEY);
    }

    private static List<String> lines(CapturedOutput output, String text) {
        return output.getAll().lines().filter(line -> line.contains(text)).toList();
    }

    private static void assertNoLeak(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(PAYMENT_KEY).doesNotContain("\tat ").doesNotContain("Caused by");
    }
}
