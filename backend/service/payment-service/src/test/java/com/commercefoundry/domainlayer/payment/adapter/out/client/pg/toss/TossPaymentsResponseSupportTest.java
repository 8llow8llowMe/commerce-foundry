package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult.NotSentReason;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결과 분류 중 HTTP 로 재현하기 어려운 갈래(원인 체인 · Retry-After 오류 · 예상 밖 예외)와 값 정규화 · 결제수단 매핑. HTTP 로 재현되는 표는 {@code TossPaymentGatewayAdapterWireMockTest}.
 */
class TossPaymentsResponseSupportTest {

    private static final String PG_ORDER_ID = "9000000001";

    private final TossPaymentsResponseSupport support = new TossPaymentsResponseSupport(CircuitBreakerRegistry.ofDefaults(), JsonMapper.builder().build());

    @Nested
    @DisplayName("I/O 오류의 원인 체인으로 '보내지 않음' 과 '모름' 을 가른다")
    class IoFailure {

        @Test
        @DisplayName("연결 거부(ConnectException) — 요청이 나가기 전이라 NotSent(CONNECT_FAILED)")
        void connectRefusedIsNotSent() {
            assertThat(support.approve(PG_ORDER_ID, throwing(ioFailure(new ConnectException("refused")))))
                .isEqualTo(new PgApprovalQueryResult.NotSent(NotSentReason.CONNECT_FAILED));
        }

        @Test
        @DisplayName("호스트 이름 해석 실패(UnknownHostException) — NotSent(CONNECT_FAILED)")
        void unknownHostIsNotSent() {
            assertThat(support.approve(PG_ORDER_ID, throwing(ioFailure(new UnknownHostException("api.tosspayments.com")))))
                .isEqualTo(new PgApprovalQueryResult.NotSent(NotSentReason.CONNECT_FAILED));
        }

        @Test
        @DisplayName("연결 실패가 원인 체인 깊이 있어도 찾는다")
        void nestedConnectFailureIsNotSent() {
            IOException wrapped = new IOException("wrapped", new ConnectException("refused"));
            assertThat(support.approve(PG_ORDER_ID, throwing(ioFailure(wrapped))))
                .isEqualTo(new PgApprovalQueryResult.NotSent(NotSentReason.CONNECT_FAILED));
        }

        @Test
        @DisplayName("read timeout(SocketTimeoutException) — 보냈을 수 있어 Unknown(TIMEOUT). 연결 타임아웃도 같은 예외라 보수적으로 같이 간다")
        void socketTimeoutIsUnknown() {
            assertThat(support.approve(PG_ORDER_ID, throwing(ioFailure(new SocketTimeoutException("Read timed out")))))
                .isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.TIMEOUT));
        }

        @Test
        @DisplayName("보내는 중 끊김(SocketException) — Unknown(TIMEOUT)")
        void connectionResetIsUnknown() {
            assertThat(support.approve(PG_ORDER_ID, throwing(ioFailure(new SocketException("Connection reset")))))
                .isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.TIMEOUT));
        }

        @Test
        @DisplayName("조회의 연결 실패는 '조회 못 함' 이다 — Unknown(SERVER_ERROR)")
        void inquiryConnectFailureIsUnknown() {
            assertThat(support.inquire(PG_ORDER_ID, null, throwing(ioFailure(new ConnectException("refused")))))
                .isEqualTo(new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR));
        }
    }

    @Test
    @DisplayName("Retry-After 가 붙은 503 은 Feign 이 RetryableException 으로 낸다 — status 로 보고 Unknown(SERVER_ERROR)")
    void retryAfterServerErrorIsUnknown() {
        RetryableException exception = new RetryableException(503, "Service Unavailable", Request.HttpMethod.POST, (Long) null, request());
        assertThat(support.approve(PG_ORDER_ID, throwing(exception))).isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR));
    }

    @Test
    @DisplayName("예상 밖 예외(인코딩 오류 등)는 보냈는지 가를 수 없어 Unknown 이다 — 예외를 밖으로 던지지 않는다")
    void unexpectedExceptionIsUnknown() {
        assertThat(support.approve(PG_ORDER_ID, throwing(new IllegalStateException("boom"))))
            .isEqualTo(new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "카드, CARD", "간편결제, EASY_PAY", "계좌이체, TRANSFER", "가상계좌, VIRTUAL_ACCOUNT", "휴대폰, MOBILE_PHONE",
        "문화상품권, GIFT_CERTIFICATE", "도서문화상품권, GIFT_CERTIFICATE", "게임문화상품권, GIFT_CERTIFICATE",
        "해외간편결제, OTHER", "CARD, OTHER", "NULL, OTHER"
    })
    @DisplayName("토스 결제수단 표시명을 PaymentMethod 로 바꾸고, 모르면 OTHER 다")
    void mapsPaymentMethod(String tossMethod, PaymentMethod expected) {
        assertThat(TossPaymentsResponseSupport.toPaymentMethod(tossMethod)).isEqualTo(expected);
    }

    @Test
    @DisplayName("정규화는 코드 포인트 기준으로 자르고, 빈 값은 null 이다")
    void normalizesByCodePoints() {
        String emoji = "😀";
        assertThat(TossPaymentsResponseSupport.normalize(emoji.repeat(60), 50)).isEqualTo(emoji.repeat(50));
        assertThat(TossPaymentsResponseSupport.normalize("  카카오페이 ", 50)).isEqualTo("카카오페이");
        assertThat(TossPaymentsResponseSupport.normalize("   ", 50)).isNull();
        assertThat(TossPaymentsResponseSupport.normalize(null, 50)).isNull();
    }

    @Test
    @DisplayName("오류 코드는 코드 모양일 때만 로그에 그대로 남긴다")
    void loggableCode() {
        assertThat(TossPaymentsResponseSupport.loggableCode("REJECT_CARD_PAYMENT")).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(TossPaymentsResponseSupport.loggableCode("BAD\r\nINJECTED")).isEqualTo(TossPaymentsResponseSupport.UNRECOGNIZED_CODE);
        assertThat(TossPaymentsResponseSupport.loggableCode("A".repeat(61))).isEqualTo(TossPaymentsResponseSupport.UNRECOGNIZED_CODE);
        assertThat(TossPaymentsResponseSupport.loggableCode(null)).isNull();
    }

    /** Feign 이 I/O 오류를 감싸는 모양 — status -1 · 원인 예외. */
    private static RetryableException ioFailure(Throwable cause) {
        return new RetryableException(-1, cause.getClass().getSimpleName() + " executing POST", Request.HttpMethod.POST, cause, (Long) null, request());
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.POST, "http://localhost/v1/payments/confirm", Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private static Supplier<byte[]> throwing(RuntimeException exception) {
        return () -> {
            throw exception;
        };
    }
}
