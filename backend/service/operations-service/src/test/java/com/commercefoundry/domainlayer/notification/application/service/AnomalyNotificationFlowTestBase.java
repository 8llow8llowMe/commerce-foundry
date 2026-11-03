package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationAnomalyRecordRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationAnomalyStateRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.in.AnomalyNotificationUseCase;
import com.commercefoundry.domainlayer.notification.application.port.in.AnomalyRetentionSchedulerUseCase;
import com.commercefoundry.domainlayer.notification.application.port.in.NotificationAdminUseCase;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalySource;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실제 웹 SSE · Feign · Kafka · 영속 저장소를 연결한다. H2/embedded와 PG17/Kafka 컨테이너에서 같은 행동을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "commerce.messaging.enabled=true", "commerce.messaging.namespace=commerce-foundry.test",
    "commerce.messaging.outbox.relay.enabled=false", "operations.notifications.anomaly.enabled=true",
    "operations.notifications.anomaly.retry-backoff=10ms", "operations.notifications.stream-heartbeat=100ms",
    "spring.datasource.url=jdbc:h2:mem:anomaly-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations"
})
@ActiveProfiles("test")
@Import(AnomalyNotificationFlowTestBase.TimeConfig.class)
@ExtendWith(OutputCaptureExtension.class)
abstract class AnomalyNotificationFlowTestBase {
    static final String PAYMENT = "commerce-foundry.test.payment.payment-approved.dlt";
    static final String EXPIRED = "commerce-foundry.test.order.order-expired.dlt";
    static final String CANCELED = "commerce-foundry.test.order.order-canceled.dlt";
    static final List<String> TOPICS = List.of(PAYMENT, EXPIRED, CANCELED,
        "commerce-foundry.test.operations.job-run", "commerce-foundry.test.operations.job-run.dlt");
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-11-03T00:59:59Z");
    private static final AtomicReference<HttpServer> SERVER = new AtomicReference<>();
    private static final String SUCCESS = "{\"dataHeader\":{\"success\":true},\"dataBody\":{\"memberIds\":[\"7\",\"8\"]}}";
    private static final AtomicReference<String> BODY = new AtomicReference<>(SUCCESS);
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final List<String> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();

    @Autowired AnomalyNotificationUseCase useCase;
    @Autowired NotificationAdminUseCase notificationAdmin;
    @Autowired AnomalyRetentionSchedulerUseCase retention;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationAnomalyStateRepository states;
    @Autowired NotificationAnomalyRecordRepository records;
    @Autowired NotificationPushPort push;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired CircuitBreakerRegistry circuits;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("anomalyNotificationPushExecutor") ThreadPoolTaskExecutor pushExecutor;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        if (SERVER.get() == null) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/internal/v1/admin-notification-recipients", exchange -> {
                    REQUESTS.incrementAndGet();
                    QUERIES.add(exchange.getRequestURI().getQuery());
                    AUTHORIZATIONS.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    String body = STATUS.get() == 200
                        ? BODY.get()
                        : "private-remote-error-sentinel";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(STATUS.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                server.start();
                SERVER.set(server);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        registry.add("spring.cloud.openfeign.client.config.adminNotificationRecipientClient.url",
            () -> "http://127.0.0.1:" + SERVER.get().getAddress().getPort());
    }

    @AfterAll
    static void stopAuthServer() {
        HttpServer server = SERVER.getAndSet(null);
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        STATUS.set(200);
        BODY.set(SUCCESS);
        REQUESTS.set(0);
        AUTHORIZATIONS.clear();
        QUERIES.clear();
        circuits.circuitBreaker("auth-service").reset();
        clock.now.set(NOW);
        await().atMost(WAIT).until(() -> pushExecutor.getActiveCount() == 0 && pushExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        notifications.deleteAll();
        records.deleteAll();
        states.deleteAll();
        ContainerTestUtils.waitForAssignment(listeners.getListenerContainer("anomaly-dlt"), 3);
    }

    @Test
    void actualDltFansOutAndSseContainsStoredIdsThenRollingSuppressionAndDuplicateNoOp(CapturedOutput output) throws Exception {
        try (StreamConnection first = connect(7); StreamConnection second = connect(8); Admin admin = admin()) {
            ProducerRecord<String, String> record = new ProducerRecord<>(PAYMENT, "private-key-sentinel", "private-payload-sentinel-not-json");
            record.headers().add("private-header", "private-header-sentinel".getBytes(StandardCharsets.UTF_8));
            var metadata = kafka.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
            await().atMost(WAIT).untilAsserted(() -> assertThat(notifications.count()).isEqualTo(2));
            String firstEvent = first.events.poll(10, TimeUnit.SECONDS);
            String secondEvent = second.events.poll(10, TimeUnit.SECONDS);
            assertThat(firstEvent).contains("ANOMALY", "CRITICAL", "/admin/payments");
            assertThat(secondEvent).contains("ANOMALY", "CRITICAL");
            List<NotificationEntity> saved = notifications.findAll();
            assertThat(saved).extracting(NotificationEntity::getRecipientMemberId).containsExactlyInAnyOrder(7L, 8L);
            assertThat(saved).extracting(NotificationEntity::getSourceId).containsOnly(saved.getFirst().getSourceId());
            assertThat(firstEvent).contains(saved.stream().filter(n -> n.getRecipientMemberId() == 7).findFirst().orElseThrow().getId().toString());
            assertThat(list(7)).contains(saved.stream().filter(n -> n.getRecipientMemberId() == 7).findFirst().orElseThrow().getId().toString());
            await().atMost(WAIT).untilAsserted(() -> assertThat(committed(admin, PAYMENT)).isEqualTo(metadata.offset() + 1));
            assertThat(QUERIES).containsExactly("afterId=0&size=100");
            var token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("auth-service"), Clock.systemUTC())
                .decode(AUTHORIZATIONS.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("auth-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("operations-service");

            int requests = REQUESTS.get();
            clock.now.set(NOW.plusSeconds(7200));
            useCase.process(new AnomalyRecordCommand(AnomalySource.PAYMENT_APPROVED, PAYMENT, metadata.partition(), metadata.offset()));
            assertThat(REQUESTS.get()).isEqualTo(requests);
            assertThat(notifications.count()).isEqualTo(2);
            clock.now.set(NOW.plusSeconds(3599));
            kafka.send(PAYMENT, "private-key-sentinel", "private-payload-sentinel-not-json").get(10, TimeUnit.SECONDS);
            await().atMost(WAIT).untilAsserted(() -> assertThat(records.count()).isEqualTo(2));
            assertThat(notifications.count()).isEqualTo(2);
            assertThat(REQUESTS.get()).isEqualTo(requests + 1);
            clock.now.set(NOW.plusSeconds(3600));
            kafka.send(PAYMENT, "private-key-sentinel", "private-payload-sentinel-not-json").get(10, TimeUnit.SECONDS);
            await().atMost(WAIT).untilAsserted(() -> assertThat(notifications.count()).isEqualTo(4));
            assertThat(records.count()).isEqualTo(3);
            assertThat(states.count()).isEqualTo(1);
            assertThat(output.getAll()).doesNotContain("private-key-sentinel", "private-payload-sentinel-not-json", "private-header-sentinel");
        }
    }

    @Test
    void actualFeignRejectsNumericJsonRatherThanCoercingItIntoCanonicalIds(CapturedOutput output) {
        BODY.set("{\"dataHeader\":{\"success\":true},\"dataBody\":{\"memberIds\":[7,8],\"debug\":\"private-wire-sentinel\"}}");
        assertThatThrownBy(() -> useCase.process(new AnomalyRecordCommand(AnomalySource.ORDER_CANCELED, CANCELED, 0, 900001)))
            .hasMessage("Admin notification recipients unavailable").hasNoCause();
        assertThat(notifications.count()).isZero();
        assertThat(records.count()).isZero();
        assertThat(states.count()).isZero();
        assertThat(output.getAll()).doesNotContain("private-wire-sentinel");
    }

    @Test
    void dbFailureRollsBackStateSeenAndEveryRecipientAndDoesNotPush() throws Exception {
        AtomicInteger pushed = new AtomicInteger();
        NotificationStream stream = ignored -> pushed.incrementAndGet();
        push.register(7, stream);
        jdbc.execute("alter table operations.notification add constraint ck_test_anomaly_recipient check (recipient_member_id <> 8)");
        try {
            assertThatThrownBy(() -> useCase.process(new AnomalyRecordCommand(AnomalySource.ORDER_CANCELED, CANCELED, 0, 900000)))
                .isInstanceOf(RuntimeException.class);
            assertThat(notifications.count()).isZero();
            assertThat(records.count()).isZero();
            assertThat(states.count()).isZero();
            assertThat(pushed.get()).isZero();
            try (Admin admin = admin()) {
                var metadata = kafka.send(CANCELED, "private-key-sentinel", "private-payload-sentinel-not-json")
                    .get(10, TimeUnit.SECONDS).getRecordMetadata();
                await().atMost(WAIT).untilAsserted(() -> assertThat(listeners.getListenerContainer("anomaly-dlt").isRunning()).isFalse());
                assertThat(committed(admin, CANCELED)).isLessThanOrEqualTo(metadata.offset());
                assertThat(notifications.count()).isZero();
                assertThat(records.count()).isZero();
                assertThat(states.count()).isZero();
                assertThat(pushed.get()).isZero();
            }
        } finally {
            jdbc.execute("alter table operations.notification drop constraint ck_test_anomaly_recipient");
            push.unregister(7, stream);
            listeners.getListenerContainer("anomaly-dlt").start();
            await().atMost(WAIT).untilAsserted(() -> assertThat(records.count()).isEqualTo(1));
        }
    }

    @Test
    void remoteFailureStopsOnlyAnomalyWithoutCommitOrRecursiveDltThenRecoversOriginal(CapturedOutput output) throws Exception {
        try (Admin admin = admin()) {
            STATUS.set(503);
            int before = REQUESTS.get();
            var metadata = kafka.send(EXPIRED, "private-key-sentinel", "private-payload-sentinel-not-json").get(10, TimeUnit.SECONDS).getRecordMetadata();
            try {
                await().atMost(WAIT).untilAsserted(() -> assertThat(listeners.getListenerContainer("anomaly-dlt").isRunning()).isFalse());
                assertThat(REQUESTS.get() - before).isEqualTo(3);
                assertThat(committed(admin, EXPIRED)).isLessThanOrEqualTo(metadata.offset());
                assertThat(notifications.count()).isZero();
                assertThat(records.count()).isZero();
                assertThat(states.count()).isZero();
                assertThat(listeners.getListenerContainers().stream().filter(c -> !c.getListenerId().equals("anomaly-dlt"))).allMatch(c -> c.isRunning());
                assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).noneMatch(topic -> topic.endsWith(".dlt.dlt"));
                assertThat(output.getAll()).contains("anomaly dlt consumer stopped")
                    .doesNotContain("private-remote-error-sentinel", "private-key-sentinel", "private-payload-sentinel-not-json");
            } finally {
                STATUS.set(200);
                circuits.circuitBreaker("auth-service").reset();
                listeners.getListenerContainer("anomaly-dlt").start();
            }
            await().atMost(WAIT).untilAsserted(() -> assertThat(committed(admin, EXPIRED)).isEqualTo(metadata.offset() + 1));
            assertThat(notifications.count()).isEqualTo(2);
        }
    }

    @Test
    void cleanupUsesRecordAgeIndependentlyOfNotificationAndStateRetentionAndReadOwnership() {
        useCase.process(new AnomalyRecordCommand(AnomalySource.PAYMENT_APPROVED, PAYMENT, 0, 800000));
        List<NotificationEntity> saved = notifications.findAll();
        long firstId = saved.stream().filter(n -> n.getRecipientMemberId() == 7).findFirst().orElseThrow().getId();
        notificationAdmin.markRead(firstId, 7, NOW);
        assertThat(notifications.countByRecipientMemberIdAndReadAtIsNull(7)).isZero();
        assertThat(notifications.countByRecipientMemberIdAndReadAtIsNull(8)).isEqualTo(1);
        clock.now.set(NOW.plus(Duration.ofDays(8)));
        assertThat(retention.purgeExpiredRecords()).isZero();
        clock.now.set(NOW.plus(Duration.ofDays(8)).plusSeconds(1));
        assertThat(retention.purgeExpiredRecords()).isEqualTo(1);
        assertThat(records.count()).isZero();
        assertThat(states.count()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(2);
    }

    private Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers")));
    }

    private long committed(Admin admin, String topic) throws Exception {
        var offsets = admin.listConsumerGroupOffsets("commerce-foundry.test.operations.anomaly-dlt").partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        var offset = offsets.get(new TopicPartition(topic, 0));
        return offset == null ? -1 : offset.offset();
    }

    private String list(long memberId) throws Exception {
        return HttpClient.newHttpClient().send(request(memberId, "/api/v1/admin/notifications"), HttpResponse.BodyHandlers.ofString()).body();
    }

    private StreamConnection connect(long memberId) throws Exception {
        var response = HttpClient.newHttpClient().send(request(memberId, "/api/v1/admin/notifications/stream"), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return new StreamConnection(response.body());
    }

    private HttpRequest request(long memberId, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(memberId, MemberRole.ADMIN).tokenValue()).GET().build();
    }

    private static final class StreamConnection implements AutoCloseable {
        private final InputStream input;
        private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        StreamConnection(InputStream input) {
            this.input = input;
            Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            events.add(line.substring(5));
                        }
                    }
                } catch (IOException closed) {
                    // 테스트가 연결을 닫는다.
                }
            });
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutableClock anomalyFlowClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
