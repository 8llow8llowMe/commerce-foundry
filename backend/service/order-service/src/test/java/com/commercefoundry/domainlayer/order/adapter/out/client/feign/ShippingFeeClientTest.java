package com.commercefoundry.domainlayer.order.adapter.out.client.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.port.out.ShippingFeeQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.ShippingScope;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Feign 파이프라인(서비스 토큰 Capability · Jackson 3 인코딩 · 디코딩 · 기본 ErrorDecoder · 타임아웃 설정)으로 delivery-service 흉내 서버를 불러 본다.
 * 원격 계약(요청 본문 모양 · 봉투 · 모르는 칸 무시 · 4xx 봉투의 resultCode 번역)과 서비스 토큰({@code aud=delivery-service}, {@code iss=order-service})을 함께 본다.
 * 배송 가능 국가 확인(1g 확인용 견적)의 요청 본문과 응답 해석도 같은 왕복으로 본다.
 *
 * <p>대상 주소만 {@code spring.cloud.openfeign.client.config.shippingFeeClient.url} 로 바꾼다 — 서비스 토큰의 {@code aud} 는 주소가 아니라 Feign 대상 이름이라 그대로다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShippingFeeClientTest {

    private static final HttpServer SERVER = startServer();
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> RESPONSE_CONTENT_TYPE = new AtomicReference<>("application/json");
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> REQUEST_BODIES = new CopyOnWriteArrayList<>();
    private static final List<List<String>> CONTENT_TYPES = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SUCCESS_HEADER = "\"dataHeader\":{\"success\":true,\"resultCode\":null,\"resultMessage\":null,\"fieldErrors\":null}";

    @Autowired
    private ShippingFeeQueryPort shippingFeeQueryPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void deliveryServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.shippingFeeClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/shipping-fees/quotes", exchange -> {
                REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                REQUEST_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                CONTENT_TYPES.add(exchange.getRequestHeaders().getOrDefault("Content-Type", List.of()));
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                byte[] body = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", RESPONSE_CONTENT_TYPE.get());
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length == 0 ? -1 : body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    /** 실패 응답 케이스가 쌓여 서킷이 열리면 뒤 케이스가 원격을 부르지 않는다 — 케이스마다 닫힌 상태로 시작한다. */
    @BeforeEach
    void reset() {
        REQUESTS.clear();
        REQUEST_BODIES.clear();
        CONTENT_TYPES.clear();
        AUTHORIZATIONS.clear();
        RESPONSE_STATUS.set(200);
        RESPONSE_CONTENT_TYPE.set("application/json");
        circuitBreakerRegistry.circuitBreaker("delivery-service").reset();
    }

    private static ShippingFeeQuoteQuery domestic() {
        return new ShippingFeeQuoteQuery(Money.of(99_000, CurrencyCode.KRW), "KR", "63100", null);
    }

    private static ShippingFeeQuoteQuery international() {
        return new ShippingFeeQuoteQuery(Money.of(12_000, CurrencyCode.USD), "US", null, 1_200);
    }

    private static String failure(String resultCode) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"x\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    private static void assertRejected(Runnable call, OrderErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    @Test
    @DisplayName("국내 — POST 본문에 금액 · 국가 · 우편번호를 싣고 무게는 싣지 않는다. 서비스 토큰 하나를 싣고, 봉투의 모든 칸을 조회 결과로 바꾼다 — 모르는 칸은 무시한다")
    void domesticRoundTrip() {
        RESPONSE_BODY.set("""
            {%s,
             "dataBody":{"shippingFee":{"amount":3000,"currency":"KRW"},"baseFee":{"amount":3000,"currency":"KRW"},"freeShippingApplied":true,
               "remoteAreaSurcharge":{"amount":3000,"currency":"KRW"},"remoteAreaName":"제주","freeShippingThreshold":{"amount":50000,"currency":"KRW"},
               "shippingPolicyVersion":3,"scope":"DOMESTIC","shippingZoneName":null,"weightFee":null,"futureField":1}}
            """.formatted(SUCCESS_HEADER));

        ShippingFeeQuoteQueryResult result = shippingFeeQueryPort.quote(domestic());

        assertThat(result).isEqualTo(new ShippingFeeQuoteQueryResult(
            Money.of(3_000, CurrencyCode.KRW), Money.of(3_000, CurrencyCode.KRW), true, Money.of(3_000, CurrencyCode.KRW), "제주",
            Money.of(50_000, CurrencyCode.KRW), 3L, ShippingScope.DOMESTIC, null, null));
        assertThat(REQUESTS).containsExactly("POST /internal/v1/shipping-fees/quotes");
        assertThat(CONTENT_TYPES).singleElement().satisfies(values -> assertThat(values).singleElement().asString().startsWith("application/json"));
        JsonNode body = JSON.readTree(REQUEST_BODIES.getFirst());
        assertThat(body.path("orderAmount").path("amount").asLong()).isEqualTo(99_000L);
        assertThat(body.path("orderAmount").path("currency").asString()).isEqualTo("KRW");
        assertThat(body.path("countryCode").asString()).isEqualTo("KR");
        assertThat(body.path("postalCode").asString()).isEqualTo("63100");
        assertThat(absent(body.path("weightGrams"))).as("국내는 무게를 싣지 않는다").isTrue();
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            assertThat(values).singleElement().asString().startsWith("Bearer ");
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("delivery-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("delivery-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("order-service");
        });
    }

    @Test
    @DisplayName("해외 — 무게를 싣고 우편번호가 없으면 null 로 보낸다. 국가 그룹 · 무게 요금을 읽고, 국내 칸(도서산간 이름) · 무료배송 기준이 null 이어도 받는다")
    void internationalRoundTrip() {
        RESPONSE_BODY.set("""
            {%s,
             "dataBody":{"shippingFee":{"amount":4500,"currency":"USD"},"baseFee":{"amount":1500,"currency":"USD"},"freeShippingApplied":false,
               "remoteAreaSurcharge":{"amount":0,"currency":"USD"},"remoteAreaName":null,"freeShippingThreshold":null,
               "shippingPolicyVersion":7,"scope":"INTERNATIONAL","shippingZoneName":"북미","weightFee":{"amount":3000,"currency":"USD"}}}
            """.formatted(SUCCESS_HEADER));

        ShippingFeeQuoteQueryResult result = shippingFeeQueryPort.quote(international());

        assertThat(result).isEqualTo(new ShippingFeeQuoteQueryResult(
            Money.of(4_500, CurrencyCode.USD), Money.of(1_500, CurrencyCode.USD), false, Money.zero(CurrencyCode.USD), null,
            null, 7L, ShippingScope.INTERNATIONAL, "북미", Money.of(3_000, CurrencyCode.USD)));
        JsonNode body = JSON.readTree(REQUEST_BODIES.getFirst());
        assertThat(body.path("orderAmount").path("amount").asLong()).isEqualTo(12_000L);
        assertThat(body.path("orderAmount").path("currency").asString()).isEqualTo("USD");
        assertThat(body.path("countryCode").asString()).isEqualTo("US");
        assertThat(absent(body.path("postalCode"))).as("해외 우편번호는 선택이다").isTrue();
        assertThat(body.path("weightGrams").asInt()).isEqualTo(1_200);
    }

    @Test
    @DisplayName("해외 우편번호가 있으면 그대로 싣는다")
    void internationalWithPostalCode() {
        RESPONSE_BODY.set("""
            {%s,
             "dataBody":{"shippingFee":{"amount":0,"currency":"USD"},"baseFee":{"amount":1500,"currency":"USD"},"freeShippingApplied":true,
               "remoteAreaSurcharge":{"amount":0,"currency":"USD"},"remoteAreaName":null,"freeShippingThreshold":{"amount":10000,"currency":"USD"},
               "shippingPolicyVersion":7,"scope":"INTERNATIONAL","shippingZoneName":"북미","weightFee":{"amount":3000,"currency":"USD"}}}
            """.formatted(SUCCESS_HEADER));

        shippingFeeQueryPort.quote(new ShippingFeeQuoteQuery(Money.of(12_000, CurrencyCode.USD), "US", "10001", 1_200));

        assertThat(JSON.readTree(REQUEST_BODIES.getFirst()).path("postalCode").asString()).isEqualTo("10001");
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "409, DELIVERY_001, SHIPPING_UNAVAILABLE",
        "400, DELIVERY_002, DESTINATION_NOT_SUPPORTED",
        "400, DELIVERY_003, DESTINATION_CURRENCY_NOT_SUPPORTED",
        "400, DELIVERY_011, SHIPPING_WEIGHT_NOT_SUPPORTED"
    })
    @DisplayName("delivery 의 업무 거절은 주문 코드로 번역한다 — 4xx 봉투의 resultCode 를 읽는다")
    void translatesBusinessRejections(int status, String resultCode, OrderErrorCode expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(resultCode));

        assertRejected(() -> shippingFeeQueryPort.quote(international()), expected);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "400, DELIVERY_118",
        "400, DELIVERY_106",
        "401, SECURITY_007",
        "403, SECURITY_005",
        "409, DELIVERY_006",
        "500, COMMON_005",
        "503, DELIVERY_001"
    })
    @DisplayName("번역하지 않는 4xx(우리 요청 · 서비스 토큰 문제)와 5xx 는 ORDER_011(503) 이다 — 5xx 본문의 코드는 보지 않는다")
    void otherFailuresAreUnavailable(int status, String resultCode) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(resultCode));

        assertRejected(() -> shippingFeeQueryPort.quote(domestic()), OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("본문이 JSON 이 아닌 4xx(프록시 오류 페이지 등) · 본문 없는 4xx 는 ORDER_011(503) 이다")
    void unreadableRejectionIsUnavailable() {
        RESPONSE_STATUS.set(400);
        RESPONSE_CONTENT_TYPE.set("text/html");
        RESPONSE_BODY.set("<html><body>Bad Request</body></html>");
        assertRejected(() -> shippingFeeQueryPort.quote(domestic()), OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);

        RESPONSE_STATUS.set(409);
        RESPONSE_BODY.set("");
        assertRejected(() -> shippingFeeQueryPort.quote(domestic()), OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("성공 봉투인데 본문이 없으면 계약 위반이라 ORDER_011(503) 이다 — 배송비를 0 으로 추측하지 않는다")
    void successEnvelopeWithoutBodyIsUnavailable() {
        RESPONSE_BODY.set("{" + SUCCESS_HEADER + ",\"dataBody\":null}");

        assertRejected(() -> shippingFeeQueryPort.quote(domestic()), OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("배송 가능 국가 확인 — 같은 견적 엔드포인트에 총 무게 1g 과 받은 금액 · 국가 · 우편번호를 싣고, 견적이 나오면(200) true 다")
    void destinationCheckSendsOneGram() {
        RESPONSE_BODY.set("""
            {%s,
             "dataBody":{"shippingFee":{"amount":1600,"currency":"USD"},"baseFee":{"amount":1500,"currency":"USD"},"freeShippingApplied":false,
               "remoteAreaSurcharge":{"amount":0,"currency":"USD"},"remoteAreaName":null,"freeShippingThreshold":null,
               "shippingPolicyVersion":7,"scope":"INTERNATIONAL","shippingZoneName":"북미","weightFee":{"amount":100,"currency":"USD"}}}
            """.formatted(SUCCESS_HEADER));

        assertThat(shippingFeeQueryPort.isDestinationSupported(Money.of(12_000, CurrencyCode.USD), "US", "10001")).isTrue();

        assertThat(REQUESTS).containsExactly("POST /internal/v1/shipping-fees/quotes");
        JsonNode body = JSON.readTree(REQUEST_BODIES.getFirst());
        assertThat(body.path("weightGrams").asInt()).isEqualTo(1);
        assertThat(body.path("orderAmount").path("amount").asLong()).isEqualTo(12_000L);
        assertThat(body.path("orderAmount").path("currency").asString()).isEqualTo("USD");
        assertThat(body.path("countryCode").asString()).isEqualTo("US");
        assertThat(body.path("postalCode").asString()).isEqualTo("10001");
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "400, DELIVERY_002, false",
        "400, DELIVERY_011, true"
    })
    @DisplayName("배송 가능 국가 확인 — 배송할 수 없는 국가(DELIVERY_002)는 false, 국가 그룹을 찾은 뒤의 무게 구간 초과(DELIVERY_011)는 국가가 배송 가능이라 true 다")
    void destinationCheckInterpretsRejections(int status, String resultCode, boolean expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(resultCode));

        assertThat(shippingFeeQueryPort.isDestinationSupported(Money.of(12_000, CurrencyCode.USD), "US", null)).isEqualTo(expected);
        assertThat(JSON.readTree(REQUEST_BODIES.getFirst()).path("weightGrams").asInt()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "409, DELIVERY_001, SHIPPING_UNAVAILABLE",
        "400, DELIVERY_003, DESTINATION_CURRENCY_NOT_SUPPORTED"
    })
    @DisplayName("배송 가능 국가 확인 — 정책 미설정 · 통화 불일치는 delivery 가 국가를 보기 전 사유라 국가를 판정하지 못했다. 번역한 코드(ORDER_012 · 014)를 그대로 던진다")
    void destinationCheckRejectsBeforeCountry(int status, String resultCode, OrderErrorCode expected) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(resultCode));

        assertRejected(() -> shippingFeeQueryPort.isDestinationSupported(Money.of(12_000, CurrencyCode.USD), "US", null), expected);
        assertThat(JSON.readTree(REQUEST_BODIES.getFirst()).path("weightGrams").asInt()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "400, DELIVERY_118",
        "401, SECURITY_007",
        "500, COMMON_005",
        "503, DELIVERY_002"
    })
    @DisplayName("배송 가능 국가 확인 — 그 밖의 실패(번역하지 않는 4xx · 5xx)는 ORDER_011(503) 을 그대로 던진다. 배송 가능 여부를 추측하지 않는다")
    void destinationCheckFailureIsUnavailable(int status, String resultCode) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(resultCode));

        assertRejected(() -> shippingFeeQueryPort.isDestinationSupported(Money.of(12_000, CurrencyCode.USD), "US", null),
            OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("배송 가능 국가 확인 — 200 이어도 견적이 계약과 다르면(해외 요청에 국내 견적) ORDER_011(503) 이다")
    void destinationCheckContractViolationIsUnavailable() {
        RESPONSE_BODY.set("""
            {%s,
             "dataBody":{"shippingFee":{"amount":3000,"currency":"USD"},"baseFee":{"amount":3000,"currency":"USD"},"freeShippingApplied":false,
               "remoteAreaSurcharge":{"amount":0,"currency":"USD"},"remoteAreaName":null,"freeShippingThreshold":null,
               "shippingPolicyVersion":3,"scope":"DOMESTIC","shippingZoneName":null,"weightFee":null}}
            """.formatted(SUCCESS_HEADER));

        assertRejected(() -> shippingFeeQueryPort.isDestinationSupported(Money.of(12_000, CurrencyCode.USD), "US", null),
            OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("배송 가능 국가 확인은 국내(KR) 배송지에 쓰지 않는다 — 원격을 부르지 않고 IllegalArgumentException 이다")
    void destinationCheckRejectsDomestic() {
        assertThatThrownBy(() -> shippingFeeQueryPort.isDestinationSupported(Money.of(99_000, CurrencyCode.KRW), "KR", "63100"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(REQUESTS).isEmpty();
    }
}
