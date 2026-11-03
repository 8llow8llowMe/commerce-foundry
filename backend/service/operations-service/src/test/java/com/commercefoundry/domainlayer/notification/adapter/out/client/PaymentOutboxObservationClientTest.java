package com.commercefoundry.domainlayer.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentOutboxObservationPort;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.test.context.*;

@SpringBootTest(properties = {"operations.jobs.worker.enabled=false", "operations.jobs.planner.enabled=false",
    "operations.notifications.payment-outbox-alert.enabled=true", "operations.notifications.payment-outbox-alert.initial-delay=1d",
    "spring.datasource.url=jdbc:h2:mem:payment-alert-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PaymentOutboxObservationClientTest {
    static final AtomicReference<String> BODY = new AtomicReference<>();
    static final AtomicReference<String> AUTH = new AtomicReference<>();
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicBoolean BLOCK = new AtomicBoolean();
    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    static final HttpServer SERVER = start();
    @Autowired PaymentOutboxObservationPort source;
    @Autowired CircuitBreakerRegistry circuits;
    @Autowired com.commercefoundry.domainlayer.notification.adapter.in.scheduler.PaymentOutboxAlertScheduler scheduler;
    @Autowired com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository notifications;
    @Autowired com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.OutboxAlertStateRepository states;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort recipients;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.commercefoundry.domainlayer.notification.application.port.out.PaymentOutboxAlertLockPort lock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean Clock clock;
    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/internal/v1/outbox-observations/ready", e -> {
                CALLS.incrementAndGet();
                AUTH.set(e.getRequestHeaders().getFirst("Authorization"));
                if (BLOCK.get()) {
                    try { RELEASE.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                byte[] bytes = BODY.get().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    e.sendResponseHeaders(STATUS.get(), bytes.length);
                    e.getResponseBody().write(bytes);
                } catch (java.io.IOException ignored) {
                    // Client timeout closes the connection.
                } finally { e.close(); }
            });
            server.start();
            return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource
    static void url(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.paymentOutboxObservationClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.cloud.openfeign.client.config.paymentOutboxObservationClient.read-timeout", () -> "150");
    }
    @BeforeEach
    void reset() {
        BODY.set(body("1"));
        STATUS.set(200); CALLS.set(0); BLOCK.set(false);
        circuits.circuitBreaker("payment-service").reset();
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-10-01T00:00:00.123456Z"));
        when(recipients.findAllActiveAdminIds()).thenReturn(List.of(7L, 8L));
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(lock).runLocked(any());
        notifications.deleteAll(); states.deleteAll();
    }
    static String body(String count) {
        return "{\"dataHeader\":{\"success\":true},\"dataBody\":{\"observedAt\":\"2026-10-01T00:00:00.123456Z\","
            + "\"readyUnpublishedCount\":" + count + ",\"oldestReadyAvailableAt\":\"2026-09-30T23:55:00Z\"}}";
    }
    @AfterAll
    static void close() { RELEASE.countDown(); SERVER.stop(0); EXECUTOR.shutdownNow(); }
    @Test
    void realFeignSendsScopedServiceToken() {
        assertThat(source.observe().readyUnpublishedCount()).isEqualTo(1);
        var jwt = InternalTokenDecoderFactory.create(TestServiceTokens.properties("payment-service"), Clock.systemUTC()).decode(AUTH.get().substring(7));
        assertThat(jwt.getAudience()).containsExactly("payment-service");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("operations-service");
        assertThat(CALLS.get()).isEqualTo(1);
    }
    @ParameterizedTest
    @ValueSource(strings = {"null", "\"1\"", "true", "-1", "1.0", "1.5", "9223372036854775808", "0"})
    void countTypeRangeAndOldestConsistencyAreStrict(String count) {
        BODY.set(body(count));
        unavailable();
    }
    @Test
    void missingAndInvalidTimestampOrEnvelopeAreRejected() {
        for (var invalid : List.of(body("1").replace("\"readyUnpublishedCount\":1,", ""),
            body("1").replace(".123456Z", ".123456789Z"), body("1").replace("\"success\":true", "\"success\":\"true\""),
            body("1").replace("2026-09-30T23:55:00Z", "2026-10-02T00:00:00Z"), "{broken", "{}")) {
            BODY.set(invalid);
            unavailable();
        }
    }
    @Test
    void transportErrorsAreSanitizedAndDoNotRetry(CapturedOutput output) {
        for (int status : List.of(401, 403, 500, 503)) {
            STATUS.set(status);
            BODY.set("SENTINEL_PRIVATE_HTTP_BODY_URL_TOKEN");
            int before = CALLS.get();
            unavailable();
            assertThat(CALLS.get()).isEqualTo(before + 1);
        }
        circuits.circuitBreaker("payment-service").transitionToOpenState();
        int before = CALLS.get();
        unavailable();
        assertThat(CALLS.get()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("SENTINEL_PRIVATE_HTTP_BODY_URL_TOKEN");
    }
    @Test
    void actualReadTimeoutIsUnavailableWithoutRetries() {
        BLOCK.set(true);
        try {
            unavailable();
            assertThat(CALLS.get()).isEqualTo(1);
        } finally { RELEASE.countDown(); }
    }
    @Test
    void realHttpThroughSchedulerPersistsStallThenSingleRecoveryAndPreservesUnavailable(CapturedOutput output) {
        scheduler.observe();
        assertThat(notifications.count()).isEqualTo(2);
        BODY.set(body("1").replace("00:00:00.123456Z", "00:01:00.123456Z"));
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-10-01T00:01:00.123456Z"));
        scheduler.observe();
        STATUS.set(503); BODY.set("SENTINEL_PRIVATE_HTTP_BODY_URL_TOKEN");
        scheduler.observe();
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(states.findById("PAYMENT").orElseThrow().getStatus())
            .isEqualTo(com.commercefoundry.domainlayer.notification.domain.model.OutboxAlertState.Status.ACTIVE);
        STATUS.set(200);
        BODY.set(body("0").replace("00:00:00.123456Z", "00:02:00.123456Z").replace("\"2026-09-30T23:55:00Z\"", "null"));
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-10-01T00:02:00.123456Z"));
        scheduler.observe();
        scheduler.observe();
        assertThat(notifications.count()).isEqualTo(4);
        assertThat(notifications.findAll()).filteredOn(n -> n.getSeverity()
            == com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity.INFO).hasSize(2);
        assertThat(output.getAll()).doesNotContain("SENTINEL_PRIVATE_HTTP_BODY_URL_TOKEN");
    }
    private void unavailable() {
        assertThatThrownBy(source::observe).isInstanceOfSatisfying(NotificationException.class, e -> {
            assertThat(e.getErrorCode().getCode()).isEqualTo("NOTIFICATION_051");
            assertThat(e.getCause()).isNull();
        });
    }
}
