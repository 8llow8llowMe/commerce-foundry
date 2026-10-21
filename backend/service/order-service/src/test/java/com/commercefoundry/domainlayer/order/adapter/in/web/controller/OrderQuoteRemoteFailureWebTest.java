package com.commercefoundry.domainlayer.order.adapter.in.web.controller;

import static com.commercefoundry.domainlayer.order.adapter.in.web.controller.OrderQuoteWebControllerTest.assertFailure;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 주문서 미리보기의 HTTP 경계를 실제 빈 · 실제 Feign · 실제 서킷으로 본다 — product · delivery 흉내 서버 하나로.
 * <ul>
 *   <li>원격 실패(5xx · read timeout · 서킷 열림)는 503 으로 번역된다 — SKU 조회는 {@code ORDER_002}, 배송비 견적은 {@code ORDER_011}</li>
 *   <li>끝 슬래시 경로({@code /api/v1/orders/quotes/})는 404 다 — 게이트웨이가 그 경로를 더 엄격한 order-write 한도로 세므로(#158),
 *       서비스에서 미리보기든 다른 핸들러든 열리면 안 된다</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderQuoteRemoteFailureWebTest {

    private static final String SKUS_PATH = "/internal/v1/skus";
    private static final String QUOTES_PATH = "/internal/v1/shipping-fees/quotes";
    private static final String QUOTE_BODY = "{\"items\":[{\"skuId\":\"9006\",\"quantity\":1}],\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}}";
    private static final String SUCCESS_HEADER = "\"dataHeader\":{\"success\":true,\"resultCode\":null,\"resultMessage\":null,\"fieldErrors\":null}";
    private static final String SKUS_BODY = "{" + SUCCESS_HEADER + ",\"dataBody\":{\"skus\":[{\"skuId\":\"9006\",\"productId\":\"7302\",\"productName\":\"테스트 치마\","
        + "\"optionValues\":[],\"optionName\":\"\",\"salePrice\":{\"amount\":59000,\"currency\":\"KRW\"},\"additionalPrice\":{\"amount\":0,\"currency\":\"KRW\"},"
        + "\"saleStatus\":{\"code\":\"ON_SALE\",\"name\":\"판매중\",\"description\":\"구매할 수 있습니다.\"},\"purchasable\":true,\"maxQuantityPerOrder\":null,"
        + "\"customOrder\":false,\"weightGrams\":850,\"hsCode\":\"6211429000\"}]}}";
    private static final String FEE_BODY = "{" + SUCCESS_HEADER + ",\"dataBody\":{\"shippingFee\":{\"amount\":0,\"currency\":\"KRW\"},"
        + "\"baseFee\":{\"amount\":3000,\"currency\":\"KRW\"},\"freeShippingApplied\":true,\"remoteAreaSurcharge\":{\"amount\":0,\"currency\":\"KRW\"},"
        + "\"remoteAreaName\":null,\"freeShippingThreshold\":{\"amount\":50000,\"currency\":\"KRW\"},\"shippingPolicyVersion\":3,\"scope\":\"DOMESTIC\","
        + "\"shippingZoneName\":null,\"weightFee\":null}}";
    private static final String UNAVAILABLE_BODY = "{\"dataHeader\":{\"success\":false,\"resultCode\":\"X_500\",\"resultMessage\":\"x\",\"fieldErrors\":null},\"dataBody\":null}";
    /** read timeout 을 넘기는 지연 — 아래 {@code read-timeout} 300ms 보다 길다. */
    private static final long SLOW_MILLIS = 1_000;

    private static final HttpServer SERVER = startServer();
    private static final Stub SKUS = new Stub();
    private static final Stub FEES = new Stub();

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void remoteServices(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("spring.cloud.openfeign.client.config.productSkuClient.url", () -> url);
        registry.add("spring.cloud.openfeign.client.config.productSkuClient.read-timeout", () -> 300);
        registry.add("spring.cloud.openfeign.client.config.shippingFeeClient.url", () -> url);
        registry.add("spring.cloud.openfeign.client.config.shippingFeeClient.read-timeout", () -> 300);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // 지연 응답이 다음 요청을 막지 않게 요청마다 스레드를 쓴다
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext(SKUS_PATH, exchange -> SKUS.respond(exchange, SKUS_BODY));
            server.createContext(QUOTES_PATH, exchange -> FEES.respond(exchange, FEE_BODY));
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

    @BeforeEach
    void reset() {
        SKUS.reset();
        FEES.reset();
        circuitBreakerRegistry.circuitBreaker("product-service").reset();
        circuitBreakerRegistry.circuitBreaker("delivery-service").reset();
    }

    private static String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }

    private MvcTestResult quote(String path) {
        return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY).exchange();
    }

    @Test
    @DisplayName("흉내 서버가 정상이면 200 — 아래 실패 시험의 기준선")
    void baseline() {
        assertThat(quote("/api/v1/orders/quotes")).hasStatusOk();
        assertThat(SKUS.calls()).isEqualTo(1);
        assertThat(FEES.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("SKU 조회 5xx · read timeout · 서킷 열림은 모두 503 ORDER_002 이고 배송비 견적까지 가지 않는다")
    void catalogFailures() {
        SKUS.status(503);
        assertFailure(quote("/api/v1/orders/quotes"), HttpStatus.SERVICE_UNAVAILABLE, "ORDER_002");

        SKUS.status(200).delay(SLOW_MILLIS);
        assertFailure(quote("/api/v1/orders/quotes"), HttpStatus.SERVICE_UNAVAILABLE, "ORDER_002");

        SKUS.delay(0);
        circuitBreakerRegistry.circuitBreaker("product-service").transitionToOpenState();
        int callsBefore = SKUS.calls();
        MvcTestResult open = quote("/api/v1/orders/quotes");
        assertFailure(open, HttpStatus.SERVICE_UNAVAILABLE, "ORDER_002");
        assertThat(open).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE.getMessage());
        assertThat(SKUS.calls()).as("서킷이 열리면 부르지 않는다").isEqualTo(callsBefore);
        assertThat(FEES.calls()).isZero();
    }

    @Test
    @DisplayName("배송비 견적 5xx · read timeout · 서킷 열림은 모두 503 ORDER_011 이다")
    void deliveryFailures() {
        FEES.status(500);
        assertFailure(quote("/api/v1/orders/quotes"), HttpStatus.SERVICE_UNAVAILABLE, "ORDER_011");

        FEES.status(200).delay(SLOW_MILLIS);
        assertFailure(quote("/api/v1/orders/quotes"), HttpStatus.SERVICE_UNAVAILABLE, "ORDER_011");

        FEES.delay(0);
        circuitBreakerRegistry.circuitBreaker("delivery-service").transitionToOpenState();
        int callsBefore = FEES.calls();
        MvcTestResult open = quote("/api/v1/orders/quotes");
        assertFailure(open, HttpStatus.SERVICE_UNAVAILABLE, "ORDER_011");
        assertThat(open).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE.getMessage());
        assertThat(FEES.calls()).as("서킷이 열리면 부르지 않는다").isEqualTo(callsBefore);
    }

    @Test
    @DisplayName("끝 슬래시 /api/v1/orders/quotes/ 는 404 다 — 미리보기 · 생성 어느 핸들러에도 닿지 않아 원격을 부르지 않는다")
    void trailingSlashIsNotMapped() {
        MvcTestResult result = mvc.post().uri("/api/v1/orders/quotes/").header(HttpHeaders.AUTHORIZATION, bearer())
            .header("Idempotency-Key", "3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11").contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(SKUS.calls()).isZero();
        assertThat(FEES.calls()).isZero();
    }

    /** 흉내 응답 하나 — 상태 · 지연을 바꿀 수 있고 받은 요청 수를 센다. 실패 상태면 본문은 실패 봉투다. */
    private static final class Stub {

        private final AtomicInteger status = new AtomicInteger(200);
        private final AtomicLong delayMillis = new AtomicLong();
        private final List<String> requests = new CopyOnWriteArrayList<>();

        void respond(HttpExchange exchange, String successBody) throws IOException {
            requests.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            sleep(delayMillis.get());
            int code = status.get();
            byte[] body = (code == 200 ? successBody : UNAVAILABLE_BODY).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(code, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (IOException clientGone) {
                // 지연 응답은 호출한 쪽이 이미 끊었다
            }
        }

        private static void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        Stub status(int code) {
            status.set(code);
            return this;
        }

        Stub delay(long millis) {
            delayMillis.set(millis);
            return this;
        }

        int calls() {
            return requests.size();
        }

        void reset() {
            status.set(200);
            delayMillis.set(0);
            requests.clear();
        }
    }
}
