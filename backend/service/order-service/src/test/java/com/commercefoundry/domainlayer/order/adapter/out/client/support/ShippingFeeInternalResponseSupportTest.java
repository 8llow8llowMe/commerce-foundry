package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.DataHeader;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ShippingFeeInternalResponseSupportTest {

    private static final Request REQUEST = Request.create(Request.HttpMethod.POST, "http://delivery-service/internal/v1/shipping-fees/quotes", Map.of(), null,
        StandardCharsets.UTF_8, null);

    private CircuitBreakerRegistry registry;
    private ShippingFeeInternalResponseSupport support;

    /** application.yml 의 delivery-service 서킷과 같은 판정(4xx 는 실패로 세지 않는다). 창은 테스트가 빨리 열리게 작게 둔다. */
    @BeforeEach
    void setUp() {
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(4)
            .minimumNumberOfCalls(4)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .ignoreExceptions(FeignException.FeignClientException.class)
            .build());
        support = new ShippingFeeInternalResponseSupport(registry, JsonMapper.builder().build());
    }

    /** 기본 ErrorDecoder 와 같은 방식으로 만든다 — 4xx 는 {@code FeignClientException}, 5xx 는 {@code FeignServerException} 이고 본문을 싣는다. */
    private static FeignException status(int status, String body) {
        feign.Response.Builder response = feign.Response.builder().status(status).reason("x").request(REQUEST).headers(Map.of());
        if (body != null) {
            response.body(body, StandardCharsets.UTF_8);
        }
        return FeignException.errorStatus("ShippingFeeClient#quote(ShippingFeeQuoteInternalRequest)", response.build());
    }

    private static String failure(String resultCode) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"x\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    private static void assertRejected(Runnable call, OrderErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static void assertUnavailable(Runnable call) {
        assertRejected(call, OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
    }

    @Nested
    @DisplayName("호출 실패 변환")
    class Call {

        @Test
        @DisplayName("성공하면 결과를 그대로 돌려준다")
        void success() {
            assertThat(support.call(() -> "ok")).isEqualTo("ok");
        }

        @ParameterizedTest(name = "status={0}")
        @ValueSource(ints = {500, 502, 503, 504})
        @DisplayName("5xx 는 ORDER_011(503) 이고 FeignException 을 밖으로 내보내지 않는다")
        void serverError(int status) {
            assertUnavailable(() -> support.call(() -> {
                throw status(status, failure("DELIVERY_001"));
            }));
        }

        @Test
        @DisplayName("전송 실패(연결 거부 · 타임아웃)도 503 이다")
        void transportFailure() {
            assertUnavailable(() -> support.call(() -> {
                throw new RetryableException(-1, "read timed out", Request.HttpMethod.POST, (Long) null, REQUEST);
            }));
        }

        @ParameterizedTest(name = "{0} {1} → {2}")
        @CsvSource({
            "409, DELIVERY_001, SHIPPING_UNAVAILABLE",
            "400, DELIVERY_002, DESTINATION_NOT_SUPPORTED",
            "400, DELIVERY_003, DESTINATION_CURRENCY_NOT_SUPPORTED",
            "400, DELIVERY_011, SHIPPING_WEIGHT_NOT_SUPPORTED"
        })
        @DisplayName("delivery 의 업무 거절 4개만 주문 코드로 번역한다 — 원인 예외를 남긴다")
        void translatesBusinessRejections(int status, String resultCode, OrderErrorCode expected) {
            assertThatThrownBy(() -> support.call(() -> {
                throw status(status, failure(resultCode));
            })).isInstanceOfSatisfying(OrderException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(expected);
                assertThat(exception.getCause()).isInstanceOf(FeignException.FeignClientException.class);
            });
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "400, DELIVERY_118",
            "400, DELIVERY_100",
            "401, SECURITY_007",
            "403, SECURITY_005",
            "404, COMMON_001",
            "409, DELIVERY_006"
        })
        @DisplayName("모르는 4xx 코드(우리 요청 · 서비스 토큰 · 허용 호출자 문제)는 503 이다")
        void unknownRejectionIsUnavailable(int status, String resultCode) {
            assertUnavailable(() -> support.call(() -> {
                throw status(status, failure(resultCode));
            }));
        }

        @ParameterizedTest(name = "body={0}")
        @ValueSource(strings = {"<html>Bad Request</html>", "not json", "null", "[]", "{}", "{\"dataHeader\":null}", "{\"dataHeader\":{\"success\":false}}",
            "{\"dataHeader\":{\"resultCode\":null}}", "{\"dataHeader\":{\"resultCode\":\"DELIVERY_002\""})
        @DisplayName("4xx 본문을 읽지 못하거나 resultCode 가 없으면 503 이다 — 잘린 JSON 안의 아는 코드도 믿지 않는다")
        void unreadableRejectionIsUnavailable(String body) {
            assertUnavailable(() -> support.call(() -> {
                throw status(400, body);
            }));
        }

        @Test
        @DisplayName("본문 없는 4xx 도 503 이다")
        void emptyRejectionIsUnavailable() {
            assertUnavailable(() -> support.call(() -> {
                throw status(409, null);
            }));
        }

        @Test
        @DisplayName("4xx 는 번역한 업무 거절이든 아니든 서킷 실패로 세지 않는다 — 배송할 수 없는 국가 요청이 몰려도 서킷이 열리지 않는다")
        void clientErrorIsNotCounted() {
            for (int index = 0; index < 10; index++) {
                assertRejected(() -> support.call(() -> {
                    throw status(400, failure("DELIVERY_002"));
                }), OrderErrorCode.DESTINATION_NOT_SUPPORTED);
                assertUnavailable(() -> support.call(() -> {
                    throw status(401, failure("SECURITY_007"));
                }));
            }

            CircuitBreaker circuitBreaker = registry.circuitBreaker(ShippingFeeInternalResponseSupport.CIRCUIT_BREAKER);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        }

        @Test
        @DisplayName("5xx 가 쌓이면 서킷이 열리고, 열린 뒤에는 원격을 부르지 않고 503 이다")
        void circuitOpens() {
            for (int index = 0; index < 4; index++) {
                assertUnavailable(() -> support.call(() -> {
                    throw status(503, null);
                }));
            }
            assertThat(registry.circuitBreaker(ShippingFeeInternalResponseSupport.CIRCUIT_BREAKER).getState()).isEqualTo(CircuitBreaker.State.OPEN);

            int[] remoteCalls = {0};
            assertThatThrownBy(() -> support.call(() -> ++remoteCalls[0]))
                .isInstanceOfSatisfying(OrderException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
                    assertThat(exception.getCause()).isInstanceOf(CallNotPermittedException.class);
                });
            assertThat(remoteCalls[0]).isZero();
        }
    }

    @Nested
    @DisplayName("응답 변환")
    class Convert {

        /** 요청한 주문 금액 — 국내 픽스처의 무료배송 기준(50,000) 이상이다. */
        private static final Money KRW_ORDER = Money.of(99_000, CurrencyCode.KRW);
        private static final Money USD_ORDER = Money.of(12_000, CurrencyCode.USD);

        private ShippingFeeQuoteInternalResponse domestic() {
            return new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(3_000, "KRW"), MoneyItem.of(3_000, "KRW"), true, MoneyItem.of(3_000, "KRW"), "제주",
                MoneyItem.of(50_000, "KRW"), 3L, "DOMESTIC", null, null);
        }

        private ShippingFeeQuoteInternalResponse international() {
            return new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(4_500, "USD"), MoneyItem.of(1_500, "USD"), false, MoneyItem.of(0, "USD"), null,
                null, 7L, "INTERNATIONAL", "북미", MoneyItem.of(3_000, "USD"));
        }

        @Test
        @DisplayName("국내 견적의 모든 칸을 조회 결과로 바꾼다")
        void convertsDomestic() {
            assertThat(support.toShippingFeeQuoteResult(Response.success(domestic()), KRW_ORDER, "KR")).isEqualTo(new ShippingFeeQuoteQueryResult(
                Money.of(3_000, CurrencyCode.KRW), Money.of(3_000, CurrencyCode.KRW), true, Money.of(3_000, CurrencyCode.KRW), "제주",
                Money.of(50_000, CurrencyCode.KRW), 3L, ShippingScope.DOMESTIC, null, null));
        }

        @Test
        @DisplayName("해외 견적 — 무료배송 기준 · 도서산간 이름이 null 이어도 받는다")
        void convertsInternational() {
            assertThat(support.toShippingFeeQuoteResult(Response.success(international()), USD_ORDER, "US")).isEqualTo(new ShippingFeeQuoteQueryResult(
                Money.of(4_500, CurrencyCode.USD), Money.of(1_500, CurrencyCode.USD), false, Money.zero(CurrencyCode.USD), null,
                null, 7L, ShippingScope.INTERNATIONAL, "북미", Money.of(3_000, CurrencyCode.USD)));
        }

        @Test
        @DisplayName("실패 봉투 · 빈 본문 · 응답 없음은 503 이다")
        void notSuccessEnvelope() {
            assertUnavailable(() -> support.toShippingFeeQuoteResult(Response.fail("DELIVERY_002", "배송할 수 없는 국가입니다."), KRW_ORDER, "KR"));
            assertUnavailable(() -> support.toShippingFeeQuoteResult(new Response<>(DataHeader.ok(), null), KRW_ORDER, "KR"));
            assertUnavailable(() -> support.toShippingFeeQuoteResult(new Response<>(null, domestic()), KRW_ORDER, "KR"));
            assertUnavailable(() -> support.toShippingFeeQuoteResult(null, KRW_ORDER, "KR"));
        }

        @Test
        @DisplayName("계약과 다른 값(빠진 금액 · 모르는 통화 · 음수 · 모르는 범위 · 빠진 정책 version · 빠진 무료배송 여부)은 배송비를 추측하지 않고 503 이다")
        void contractViolation() {
            ShippingFeeQuoteInternalResponse valid = domestic();
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(null, valid.baseFee(), true, valid.remoteAreaSurcharge(), null,
                null, 3L, "DOMESTIC", null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(MoneyItem.of(3_000, "JPY"), valid.baseFee(), true, valid.remoteAreaSurcharge(), null,
                null, 3L, "DOMESTIC", null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(MoneyItem.of(-1, "KRW"), valid.baseFee(), true, valid.remoteAreaSurcharge(), null,
                null, 3L, "DOMESTIC", null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), true, null, null,
                null, 3L, "DOMESTIC", null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), true, valid.remoteAreaSurcharge(), null,
                null, 3L, "OVERSEAS", null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), true, valid.remoteAreaSurcharge(), null,
                null, 3L, null, null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), true, valid.remoteAreaSurcharge(), null,
                null, null, "DOMESTIC", null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), null, valid.remoteAreaSurcharge(), null,
                null, 3L, "DOMESTIC", null, null)));
        }

        @Test
        @DisplayName("견적 금액의 통화가 섞여 있거나 주문 통화와 다르면 503 이다 — delivery 는 통화가 다르면 견적하지 않는다(DELIVERY_003)")
        void currencyMismatch() {
            ShippingFeeQuoteInternalResponse valid = domestic();
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), MoneyItem.of(3_000, "USD"), true, valid.remoteAreaSurcharge(), null,
                null, 3L, "DOMESTIC", null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), true, valid.remoteAreaSurcharge(), null,
                MoneyItem.of(50, "USD"), 3L, "DOMESTIC", null, null)));
            assertUnavailable(() -> support.toShippingFeeQuoteResult(Response.success(valid), USD_ORDER, "KR"));
        }

        @Test
        @DisplayName("견적 범위가 요청 국가와 다르면 503 이다 — KR 요청에 해외 견적, 해외 요청에 국내 견적")
        void scopeMismatch() {
            assertUnavailable(() -> support.toShippingFeeQuoteResult(Response.success(international()), USD_ORDER, "KR"));
            assertUnavailable(() -> support.toShippingFeeQuoteResult(Response.success(domestic()), KRW_ORDER, "US"));
        }

        @Test
        @DisplayName("국내 견적에 해외 칸(국가 그룹 · 무게 요금)이 있으면 503 이다")
        void domesticWithInternationalFields() {
            ShippingFeeQuoteInternalResponse valid = domestic();
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), true, valid.remoteAreaSurcharge(), "제주",
                valid.freeShippingThreshold(), 3L, "DOMESTIC", "북미", null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), true, valid.remoteAreaSurcharge(), "제주",
                valid.freeShippingThreshold(), 3L, "DOMESTIC", null, MoneyItem.of(1_000, "KRW"))));
        }

        @Test
        @DisplayName("해외 견적에 국가 그룹 · 무게 요금이 없거나, 국내 칸(도서산간 추가 배송비 · 구간 이름)이 있으면 503 이다")
        void internationalFieldViolation() {
            ShippingFeeQuoteInternalResponse valid = international();
            assertUnavailable(() -> convertInternational(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), false, valid.remoteAreaSurcharge(),
                null, null, 7L, "INTERNATIONAL", null, valid.weightFee())));
            assertUnavailable(() -> convertInternational(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), false, valid.remoteAreaSurcharge(),
                null, null, 7L, "INTERNATIONAL", "북미", null)));
            assertUnavailable(() -> convertInternational(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), false, MoneyItem.of(500, "USD"),
                null, null, 7L, "INTERNATIONAL", "북미", valid.weightFee())));
            assertUnavailable(() -> convertInternational(new ShippingFeeQuoteInternalResponse(valid.shippingFee(), valid.baseFee(), false, valid.remoteAreaSurcharge(),
                "제주", null, 7L, "INTERNATIONAL", "북미", valid.weightFee())));
        }

        @Test
        @DisplayName("배송비가 delivery 산식과 다르면 503 이다 — 국내 (무료면 0, 아니면 기본) + 도서산간, 해외 무료면 0, 아니면 기본료 + 무게 요금")
        void feeFormulaViolation() {
            // 국내: 무료배송이 아닌데 도서산간 추가 배송비를 빠뜨렸다 · 무료배송인데 기본 배송비를 받았다
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(3_000, "KRW"), MoneyItem.of(3_000, "KRW"), false, MoneyItem.of(3_000, "KRW"), "제주", null, 3L, "DOMESTIC", null, null)));
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(6_000, "KRW"), MoneyItem.of(3_000, "KRW"), true, MoneyItem.of(3_000, "KRW"), "제주", MoneyItem.of(50_000, "KRW"), 3L, "DOMESTIC", null, null)));
            // 해외: 무게 요금을 빠뜨렸다 · 무료배송인데 무게 요금을 받았다
            assertUnavailable(() -> convertInternational(new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(1_500, "USD"), MoneyItem.of(1_500, "USD"), false, MoneyItem.of(0, "USD"), null, null, 7L, "INTERNATIONAL", "북미", MoneyItem.of(3_000, "USD"))));
            assertUnavailable(() -> convertInternational(new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(3_000, "USD"), MoneyItem.of(1_500, "USD"), true, MoneyItem.of(0, "USD"), null, MoneyItem.of(10_000, "USD"), 7L, "INTERNATIONAL", "북미",
                MoneyItem.of(3_000, "USD"))));
        }

        @Test
        @DisplayName("무료배송 기준 없이 무료배송이 적용됐으면 503 이다")
        void freeShippingWithoutThreshold() {
            assertUnavailable(() -> convert(new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(0, "KRW"), MoneyItem.of(3_000, "KRW"), true, MoneyItem.of(0, "KRW"), null, null, 3L, "DOMESTIC", null, null)));
            assertUnavailable(() -> convertInternational(new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(0, "USD"), MoneyItem.of(1_500, "USD"), true, MoneyItem.of(0, "USD"), null, null, 7L, "INTERNATIONAL", "북미", MoneyItem.of(3_000, "USD"))));
        }

        @Test
        @DisplayName("무료배송 판정은 주문 금액이 기준 이상인가와 같아야 한다 — 기준과 같으면 무료, 1 모자라면 유료. 어긋나면 503 이다")
        void freeShippingMatchesOrderAmount() {
            ShippingFeeQuoteInternalResponse free = new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(0, "KRW"), MoneyItem.of(3_000, "KRW"), true, MoneyItem.of(0, "KRW"), null, MoneyItem.of(50_000, "KRW"), 3L, "DOMESTIC", null, null);
            ShippingFeeQuoteInternalResponse paid = new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(3_000, "KRW"), MoneyItem.of(3_000, "KRW"), false, MoneyItem.of(0, "KRW"), null, MoneyItem.of(50_000, "KRW"), 3L, "DOMESTIC", null, null);
            Money atThreshold = Money.of(50_000, CurrencyCode.KRW);
            Money belowThreshold = Money.of(49_999, CurrencyCode.KRW);

            assertThat(support.toShippingFeeQuoteResult(Response.success(free), atThreshold, "KR").freeShippingApplied()).isTrue();
            assertThat(support.toShippingFeeQuoteResult(Response.success(paid), belowThreshold, "KR").freeShippingApplied()).isFalse();
            assertUnavailable(() -> support.toShippingFeeQuoteResult(Response.success(free), belowThreshold, "KR"));
            assertUnavailable(() -> support.toShippingFeeQuoteResult(Response.success(paid), atThreshold, "KR"));

            ShippingFeeQuoteInternalResponse internationalFree = new ShippingFeeQuoteInternalResponse(
                MoneyItem.of(0, "USD"), MoneyItem.of(1_500, "USD"), true, MoneyItem.of(0, "USD"), null, MoneyItem.of(10_000, "USD"), 7L, "INTERNATIONAL", "북미",
                MoneyItem.of(3_000, "USD"));
            assertThat(support.toShippingFeeQuoteResult(Response.success(internationalFree), Money.of(10_000, CurrencyCode.USD), "US").shippingFee())
                .isEqualTo(Money.zero(CurrencyCode.USD));
            assertUnavailable(() -> support.toShippingFeeQuoteResult(Response.success(internationalFree), Money.of(9_999, CurrencyCode.USD), "US"));
        }

        private void convert(ShippingFeeQuoteInternalResponse body) {
            support.toShippingFeeQuoteResult(Response.success(body), KRW_ORDER, "KR");
        }

        private void convertInternational(ShippingFeeQuoteInternalResponse body) {
            support.toShippingFeeQuoteResult(Response.success(body), USD_ORDER, "US");
        }
    }

    @Nested
    @DisplayName("로그에 남기는 resultCode")
    class LoggedResultCode {

        @Test
        @DisplayName("모르는 4xx 의 resultCode 에 CR/LF 가 들어 있어도 로그에 그대로 들어가지 않는다")
        void unknownRejectionLogIsSanitized(CapturedOutput output) {
            assertUnavailable(() -> support.call(() -> {
                throw status(400, failure("DELIVERY_118\\r\\nINJECTED fake log line"));
            }));

            assertThat(output.getOut()).contains("delivery-service rejected internal request. status=400, resultCode=<unrecognized>").doesNotContain("INJECTED");
        }
    }
}
