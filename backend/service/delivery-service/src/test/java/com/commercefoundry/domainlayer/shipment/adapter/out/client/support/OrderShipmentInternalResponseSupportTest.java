package com.commercefoundry.domainlayer.shipment.adapter.out.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.common.dto.DataHeader;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException.Failure;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.shared.commerce.ShippingScope;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * order-service 출고 반영 응답 번역 — {@code order-flow-guide.md} §4 실패 표. 409 를 상태가 아니라 코드로 나누는지, 서킷이 업무 거절을 실패로 세지 않는지,
 * 로그에 본문 · 메시지가 남지 않는지 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderShipmentInternalResponseSupportTest {

    private static final long ORDER_ID = 4611686018427387904L;
    private static final Request REQUEST = Request.create(Request.HttpMethod.POST, "http://order-service/internal/v1/orders/1/shipment", Map.of(), null,
        StandardCharsets.UTF_8, null);

    private CircuitBreakerRegistry registry;
    private OrderShipmentInternalResponseSupport support;

    /** application.yml 의 order-service 서킷과 같은 판정(4xx 는 실패로 세지 않는다). 창은 테스트가 빨리 열리게 작게 둔다. */
    @BeforeEach
    void setUp() {
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(4)
            .minimumNumberOfCalls(4)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .ignoreExceptions(FeignException.FeignClientException.class)
            .build());
        support = new OrderShipmentInternalResponseSupport(registry, JsonMapper.builder().build());
    }

    /** 기본 ErrorDecoder 와 같은 방식 — 4xx 는 {@code FeignClientException}, 5xx 는 {@code FeignServerException} 이고 본문을 싣는다. */
    private static FeignException status(int status, String body) {
        feign.Response.Builder response = feign.Response.builder().status(status).reason("x").request(REQUEST).headers(Map.of());
        if (body != null) {
            response.body(body, StandardCharsets.UTF_8);
        }
        return FeignException.errorStatus("OrderShipmentClient#ship(long,OrderShipmentInternalRequest)", response.build());
    }

    private static String failure(String resultCode, String message) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"" + message + "\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    private OrderShipmentException rejected(FeignException exception) {
        try {
            support.call(() -> {
                throw exception;
            });
        } catch (OrderShipmentException translated) {
            return translated;
        }
        throw new AssertionError("OrderShipmentException 이 나야 한다");
    }

    @Nested
    @DisplayName("호출 실패 번역")
    class Call {

        @ParameterizedTest(name = "{0} {1} → {2}")
        @CsvSource({
            "404, ORDER_001, NOT_FOUND",
            "409, ORDER_020, NOT_SHIPPABLE",
            "409, ORDER_025, CONFLICT_RETRY",
            "400, ORDER_159, INVALID_TIME",
            "400, ORDER_160, INVALID_TIME",
            "400, ORDER_161, INVALID_TIME"
        })
        @DisplayName("아는 상태 · 코드 쌍만 번역하고 원인 예외를 잇는다")
        void translatesKnownRejections(int status, String resultCode, Failure expected) {
            OrderShipmentException exception = rejected(status(status, failure(resultCode, "x")));

            assertThat(exception.getFailure()).isEqualTo(expected);
            assertThat(exception.getCause()).isInstanceOf(FeignException.FeignClientException.class);
        }

        @Test
        @DisplayName("같은 409 라도 ORDER_020 은 행을 지우고 ORDER_025 는 행을 남긴다 — 코드로 나눈다")
        void conflictsSplitByCode() {
            assertThat(rejected(status(409, failure("ORDER_020", "x"))).getFailure().discardsPending()).isTrue();
            assertThat(rejected(status(409, failure("ORDER_025", "x"))).getFailure().discardsPending()).isFalse();
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "409, ORDER_001",
            "404, ORDER_020",
            "400, ORDER_141",
            "400, ORDER_100",
            "409, ORDER_023",
            "401, SECURITY_007",
            "403, SECURITY_005",
            "404, COMMON_404"
        })
        @DisplayName("상태 · 코드가 계약과 다르면(다른 상태로 온 같은 코드 · 모르는 코드 · 인증 오류) 결과 모름이다")
        void unknownRejectionIsUnknown(int status, String resultCode) {
            assertThat(rejected(status(status, failure(resultCode, "x"))).getFailure()).isEqualTo(Failure.UNKNOWN);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "not json", "{\"dataHeader\":null}"})
        @DisplayName("4xx 본문이 없거나 읽을 수 없으면 결과 모름이다 — 상태만 보고 지우지 않는다")
        void unreadableBodyIsUnknown(String body) {
            assertThat(rejected(status(404, body)).getFailure()).isEqualTo(Failure.UNKNOWN);
            assertThat(rejected(status(409, body)).getFailure()).isEqualTo(Failure.UNKNOWN);
        }

        @ParameterizedTest(name = "status={0}")
        @ValueSource(ints = {500, 502, 503, 504})
        @DisplayName("5xx 는 결과 모름이다")
        void serverErrorIsUnknown(int status) {
            assertThat(rejected(status(status, failure("ORDER_001", "x"))).getFailure()).isEqualTo(Failure.UNKNOWN);
        }

        @Test
        @DisplayName("타임아웃 · 전송 실패는 결과 모름이다")
        void transportFailureIsUnknown() {
            assertThat(rejected(new RetryableException(-1, "read timed out", Request.HttpMethod.POST, (Long) null, REQUEST)).getFailure()).isEqualTo(Failure.UNKNOWN);
        }

        @Test
        @DisplayName("업무 거절(4xx)은 서킷 실패로 세지 않고, 5xx 가 쌓이면 서킷이 열려 호출 없이 결과 모름이다")
        void circuit() {
            for (int i = 0; i < 4; i++) {
                rejected(status(409, failure("ORDER_020", "x")));
            }
            assertThat(registry.circuitBreaker(OrderShipmentInternalResponseSupport.CIRCUIT_BREAKER).getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            for (int i = 0; i < 4; i++) {
                rejected(status(503, null));
            }
            OrderShipmentException open = rejected(status(503, null));

            assertThat(registry.circuitBreaker(OrderShipmentInternalResponseSupport.CIRCUIT_BREAKER).getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(open.getFailure()).isEqualTo(Failure.UNKNOWN);
            assertThat(open.getCause()).isInstanceOf(CallNotPermittedException.class);
        }

        @Test
        @DisplayName("ORDER_020 메시지에서 현재 상태 이름만 꺼낸다")
        void extractsStatusName() {
            OrderShipmentException exception = rejected(status(409, failure("ORDER_020", "현재 주문 상태(취소 요청)에서는 할 수 없는 요청입니다.")));

            assertThat(exception.getOrderStatusName()).isEqualTo("취소 요청");
            assertThat(exception.toShipmentException().getMessage()).isEqualTo("취소 요청 상태라 출고할 수 없습니다.");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "현재 주문 상태(<script>)에서는 할 수 없는 요청입니다.",
            "현재 주문 상태(아주아주아주아주아주아주아주아주아주아주아주 긴 상태)에서는 할 수 없는 요청입니다.",
            "현재 주문 상태(   )에서는 할 수 없는 요청입니다.",
            "다른 문구(결제 대기)"
        })
        @DisplayName("상태 이름이 짧은 한글이 아니면 원격 문자열을 옮기지 않고 일반 문구로 낸다")
        void rejectsOddStatusName(String message) {
            OrderShipmentException exception = rejected(status(409, failure("ORDER_020", message)));

            assertThat(exception.getFailure()).isEqualTo(Failure.NOT_SHIPPABLE);
            assertThat(exception.getOrderStatusName()).isNull();
            assertThat(exception.toShipmentException().getMessage()).isEqualTo("현재 주문 상태라 출고할 수 없습니다.");
        }

        @Test
        @DisplayName("로그에는 상태 · 코드 모양 resultCode 만 남고 본문 · 메시지 · 임의 문자열은 남지 않는다")
        void logsWithoutBody(CapturedOutput output) {
            rejected(status(400, failure("NOT A CODE\\r\\nforged", "비밀 메시지")));

            assertThat(output).contains("status=400").contains("resultCode=<unrecognized>").doesNotContain("비밀 메시지").doesNotContain("forged");
        }
    }

    @Nested
    @DisplayName("200 응답 확인")
    class Confirmation {

        private Response<OrderFulfillmentInternalResponse> ok(String orderId, String memberId, String status, String countryCode) {
            return Response.success(new OrderFulfillmentInternalResponse(orderId, memberId, status, countryCode));
        }

        @Test
        @DisplayName("회원 · 상태 · 배송 국가(→ 범위)를 옮긴다")
        void converts() {
            OrderShipmentConfirmation confirmation = support.toConfirmation(ok(String.valueOf(ORDER_ID), "4611686018427387905", "SHIPPING", "US"), ORDER_ID);

            assertThat(confirmation.memberId()).isEqualTo(4611686018427387905L);
            assertThat(confirmation.orderStatus()).isEqualTo("SHIPPING");
            assertThat(confirmation.shippingScope()).isEqualTo(ShippingScope.INTERNATIONAL);
            assertThat(support.toConfirmation(ok(String.valueOf(ORDER_ID), "5", "SHIPPING", "KR"), ORDER_ID).shippingScope()).isEqualTo(ShippingScope.DOMESTIC);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "kr", "KOR"})
        @DisplayName("배송 국가를 읽지 못하면 범위 없이 받는다 — 주문은 이미 배송 중이다")
        void unreadableCountry(String countryCode) {
            assertThat(support.toConfirmation(ok(String.valueOf(ORDER_ID), "5", "SHIPPING", countryCode), ORDER_ID).shippingScope()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(value = {
            "다른 주문 | 1 | 5 | SHIPPING",
            "회원 없음 | " + ORDER_ID + " | NULL | SHIPPING",
            "회원 0 | " + ORDER_ID + " | 0 | SHIPPING",
            "회원 앞자리 0 | " + ORDER_ID + " | 05 | SHIPPING",
            "회원 음수 | " + ORDER_ID + " | -5 | SHIPPING",
            "회원 숫자 아님 | " + ORDER_ID + " | abc | SHIPPING",
            "상태 없음 | " + ORDER_ID + " | 5 | NULL",
            "상태 모양 | " + ORDER_ID + " | 5 | shipping"
        }, delimiter = '|', nullValues = "NULL")
        @DisplayName("계약과 다른 200 은 결과 모름(행 유지)이고 error 로그다 — 코드를 고치기 전까지 미반영으로 남으므로 운영 확인이 필요하다")
        void contractViolation(String name, String orderId, String memberId, String status) {
            Logger logger = (Logger) LoggerFactory.getLogger(OrderShipmentInternalResponseSupport.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                assertThatThrownBy(() -> support.toConfirmation(ok(orderId, memberId, status, "KR"), ORDER_ID))
                    .isInstanceOfSatisfying(OrderShipmentException.class, exception -> assertThat(exception.getFailure()).isEqualTo(Failure.UNKNOWN));
                assertThat(appender.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).startsWith("order-service shipment response violates contract.");
                });
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("성공 봉투가 아니거나 본문이 없으면 결과 모름이다")
        void notSuccessEnvelope() {
            assertThatThrownBy(() -> support.toConfirmation(null, ORDER_ID)).isInstanceOf(OrderShipmentException.class);
            assertThatThrownBy(() -> support.toConfirmation(new Response<>(DataHeader.ok(), null), ORDER_ID)).isInstanceOf(OrderShipmentException.class);
            assertThatThrownBy(() -> support.toConfirmation(Response.fail("ORDER_001", "x"), ORDER_ID)).isInstanceOf(OrderShipmentException.class);
        }
    }
}
