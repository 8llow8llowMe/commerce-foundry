package com.commercefoundry.domainlayer.onboarding.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.ShippingPolicyFactQueryPort;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ShippingPolicyFactQueryResult;
import com.commercefoundry.domainlayer.onboarding.application.service.processor.OnboardingTaskProcessor;
import com.commercefoundry.domainlayer.onboarding.domain.model.MemberTaskStatus;
import com.commercefoundry.domainlayer.onboarding.domain.model.StoreTaskStatus;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

/**
 * 실제 Feign 파이프라인(서비스 토큰 Capability · Jackson 3 디코딩 · 기본 ErrorDecoder · 타임아웃 설정 · 서킷)으로 delivery-service 흉내 서버를 불러 본다 (#357 PR-B2).
 * 요청 모양(GET · memberId 쿼리) · 서비스 토큰({@code aud=delivery-service}, {@code iss=operations-service}) · 봉투 해제 · 실패 분류(→ {@code ONBOARDING_030})와,
 * 그 실패를 Processor 가 {@code UNAVAILABLE} 로 흡수하는지를 함께 본다.
 *
 * <p>대상 주소와 이 클라이언트의 read timeout 만 바꾼다 — 서비스 토큰의 {@code aud} 는 주소가 아니라 Feign 대상 이름이라 그대로다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShippingPolicyFactClientTest {

    private static final HttpServer SERVER = startServer();
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicLong RESPONSE_DELAY_MILLIS = new AtomicLong();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTHORIZATIONS = new CopyOnWriteArrayList<>();

    private static final String SUCCESS_HEADER = "\"dataHeader\":{\"success\":true,\"resultCode\":null,\"resultMessage\":null,\"fieldErrors\":null}";
    private static final long MEMBER = 7001L;

    @Autowired
    private ShippingPolicyFactQueryPort shippingPolicyFactQueryPort;

    @Autowired
    private OnboardingTaskProcessor onboardingTaskProcessor;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void deliveryServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.shippingPolicyFactClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.cloud.openfeign.client.config.shippingPolicyFactClient.read-timeout", () -> "500");
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/internal/v1/onboarding-facts/shipping-policy", exchange -> {
                REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery());
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                long delay = RESPONSE_DELAY_MILLIS.get();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length == 0 ? -1 : body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                } catch (IOException closedByClient) {
                    // 타임아웃 케이스 — 클라이언트가 먼저 끊었다
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

    /** 실패 케이스가 쌓여 서킷이 열리면 뒤 케이스가 원격을 부르지 않는다 — 케이스마다 닫힌 상태로 시작한다. */
    @BeforeEach
    void reset() {
        REQUESTS.clear();
        AUTHORIZATIONS.clear();
        RESPONSE_STATUS.set(200);
        RESPONSE_DELAY_MILLIS.set(0);
        RESPONSE_BODY.set("{" + SUCCESS_HEADER + ",\"dataBody\":{\"storeReady\":true,\"memberFirstSavedAt\":null}}");
        circuitBreakerRegistry.circuitBreaker("delivery-service").reset();
    }

    private static String failure(String resultCode) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"x\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> shippingPolicyFactQueryPort.findShippingPolicyFact(MEMBER)).isInstanceOfSatisfying(OnboardingException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(OnboardingErrorCode.DELIVERY_SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("GET 에 memberId 쿼리를 싣고 서비스 토큰 하나를 싣는다 — 봉투를 풀어 storeReady · memberFirstSavedAt(UTC) 를 읽고 모르는 칸은 무시한다")
    void roundTrip() {
        RESPONSE_BODY.set("{" + SUCCESS_HEADER + ",\"dataBody\":{\"storeReady\":true,\"memberFirstSavedAt\":\"2026-10-27T02:00:00Z\",\"futureField\":1}}");

        ShippingPolicyFactQueryResult result = shippingPolicyFactQueryPort.findShippingPolicyFact(MEMBER);

        assertThat(result).isEqualTo(new ShippingPolicyFactQueryResult(true, Instant.parse("2026-10-27T02:00:00Z")));
        assertThat(REQUESTS).containsExactly("GET /internal/v1/onboarding-facts/shipping-policy?memberId=" + MEMBER);
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            assertThat(values).singleElement().asString().startsWith("Bearer ");
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("delivery-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("delivery-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("operations-service");
        });
    }

    @Test
    @DisplayName("memberFirstSavedAt 이 null 이면 null 로 읽는다 — 다른 운영자가 만든 정책만 있는 경우")
    void nullFirstSavedAt() {
        assertThat(shippingPolicyFactQueryPort.findShippingPolicyFact(MEMBER)).isEqualTo(new ShippingPolicyFactQueryResult(true, null));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "400, DELIVERY_139",
        "401, SECURITY_007",
        "403, SECURITY_005",
        "404, COMMON_004",
        "500, COMMON_005",
        "503, DELIVERY_013"
    })
    @DisplayName("4xx(우리 요청 · 서비스 토큰 · 허용 호출자 설정 문제)와 5xx 는 모두 ONBOARDING_030 이다")
    void failuresAreUnavailable(int status, String resultCode) {
        RESPONSE_STATUS.set(status);
        RESPONSE_BODY.set(failure(resultCode));

        assertUnavailable();
    }

    @Test
    @DisplayName("read timeout 을 넘기면 ONBOARDING_030 이다 — 60초를 기다리지 않는다")
    void timeoutIsUnavailable() {
        RESPONSE_DELAY_MILLIS.set(1_500);

        long started = System.nanoTime();
        assertUnavailable();
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_500);
    }

    @Test
    @DisplayName("성공 봉투인데 본문 · storeReady 가 없으면 계약 위반이라 ONBOARDING_030 이다 — false 로 추측하지 않는다")
    void contractViolationIsUnavailable() {
        RESPONSE_BODY.set("{" + SUCCESS_HEADER + ",\"dataBody\":null}");
        assertUnavailable();

        RESPONSE_BODY.set("{" + SUCCESS_HEADER + ",\"dataBody\":{\"memberFirstSavedAt\":null}}");
        assertUnavailable();
    }

    @Test
    @DisplayName("서킷이 열려 있으면 원격을 부르지 않고 ONBOARDING_030 이다")
    void openCircuitIsUnavailable() {
        circuitBreakerRegistry.circuitBreaker("delivery-service").transitionToOpenState();

        assertUnavailable();
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    @DisplayName("Processor 는 원격 장애(5xx)를 예외 없이 UNAVAILABLE 업무로 흡수한다")
    void processorAbsorbsOutage() {
        RESPONSE_STATUS.set(503);
        RESPONSE_BODY.set(failure("COMMON_005"));

        assertThat(onboardingTaskProcessor.getMine(MEMBER)).singleElement().satisfies(task -> {
            assertThat(task.storeStatus()).isEqualTo(StoreTaskStatus.UNAVAILABLE);
            assertThat(task.myStatus()).isEqualTo(MemberTaskStatus.UNAVAILABLE);
        });
    }
}
