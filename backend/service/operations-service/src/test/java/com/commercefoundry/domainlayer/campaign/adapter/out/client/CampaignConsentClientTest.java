package com.commercefoundry.domainlayer.campaign.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.campaign.application.exception.CampaignErrorCode;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignException;
import com.commercefoundry.domainlayer.campaign.application.port.out.CampaignConsentQueryPort;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class CampaignConsentClientTest {
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicLong DELAY = new AtomicLong();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer SERVER = startServer();
    private static final String HEADER = "\"dataHeader\":{\"success\":true}";
    @Autowired
    private CampaignConsentQueryPort port;
    @Autowired
    private CircuitBreakerRegistry circuits;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.campaignConsentClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.cloud.openfeign.client.config.campaignConsentClient.read-timeout", () -> "400");
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/internal/v1/marketing-consents/query", exchange -> {
                REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " +
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                String response = BODY.get();
                int status = STATUS.get();
                try {
                    Thread.sleep(DELAY.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException timeoutClosedConnection) {
                    // 타임아웃 테스트에서 클라이언트가 먼저 닫는다.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
        EXECUTOR.shutdownNow();
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        AUTHORIZATIONS.clear();
        STATUS.set(200);
        DELAY.set(0);
        BODY.set(success("[{\"memberId\":\"1\",\"marketingAgreed\":false},{\"memberId\":\"2\",\"marketingAgreed\":true}]"));
        circuits.circuitBreaker("auth-service").reset();
    }

    private static String success(String members) {
        return "{" + HEADER + ",\"dataBody\":{\"members\":" + members + "}}";
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> port.findConsents(List.of(2L, 1L)))
            .isInstanceOfSatisfying(CampaignException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(CampaignErrorCode.CONSENT_SERVICE_UNAVAILABLE);
                assertThat(exception.getCause()).isNull();
                assertThat(exception.getMessage()).doesNotContain("private-source-body", "memberIds");
            });
    }

    @Test
    void actualFeignBodyAndServiceTokenAndUnorderedResult() {
        assertThat(port.findConsents(List.of(2L, 1L)).agreedMemberIds()).containsExactly(2L);
        assertThat(REQUESTS).containsExactly("POST /internal/v1/marketing-consents/query {\"memberIds\":[2,1]}");
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            assertThat(values).hasSize(1);
            var token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("auth-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("auth-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("operations-service");
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 503})
    void remoteFailureDoesNotRetryOrLeak(int status) {
        STATUS.set(status);
        BODY.set("private-source-body");
        assertUnavailable();
        assertThat(REQUESTS).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"dataHeader\":{\"success\":false},\"dataBody\":{\"members\":[]}}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":null}", "{\"dataHeader\":{\"success\":true},\"dataBody\":{}}"})
    void brokenEnvelope(String body) {
        BODY.set(body);
        assertUnavailable();
        assertThat(REQUESTS).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[null]", "[{\"memberId\":\"1\",\"marketingAgreed\":true}]",
        "[{\"memberId\":\"1\",\"marketingAgreed\":true},{\"memberId\":\"1\",\"marketingAgreed\":false}]",
        "[{\"memberId\":\"1\",\"marketingAgreed\":true},{\"memberId\":\"3\",\"marketingAgreed\":false}]",
        "[{\"memberId\":\"2\"},{\"memberId\":\"1\",\"marketingAgreed\":false}]",
        "[{\"memberId\":\"x\",\"marketingAgreed\":true},{\"memberId\":\"1\",\"marketingAgreed\":false}]",
        "[{\"memberId\":\"02\",\"marketingAgreed\":true},{\"memberId\":\"1\",\"marketingAgreed\":false}]"})
    void malformedMembersFailClosed(String members) {
        BODY.set(success(members));
        assertUnavailable();
        assertThat(REQUESTS).hasSize(1);
        assertThat(circuits.circuitBreaker("auth-service").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void timeoutAndOpenCircuitFailClosed() {
        DELAY.set(1_200);
        assertUnavailable();
        assertThat(REQUESTS).hasSize(1);
        circuits.circuitBreaker("auth-service").transitionToOpenState();
        assertUnavailable();
        assertThat(REQUESTS).hasSize(1);
    }

    @Test
    void contractFailuresOpenCircuit() {
        BODY.set(success("[]"));
        for (int i = 0; i < 10; i++) {
            assertUnavailable();
        }
        assertThat(REQUESTS).hasSize(10);
        assertUnavailable();
        assertThat(REQUESTS).hasSize(10);
    }
}
