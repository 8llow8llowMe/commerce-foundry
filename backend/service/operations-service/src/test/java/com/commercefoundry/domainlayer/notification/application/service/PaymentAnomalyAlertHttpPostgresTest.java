package com.commercefoundry.domainlayer.notification.application.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.PaymentAnomalyAlertScheduler;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.NotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.PaymentAnomalyAlertStateRepository;
import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Loopback payment HTTP -> production Feign/JWT -> PostgreSQL commit -> registered SSE stream. */
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "operations.notifications.payment-anomaly-alert.enabled=true",
    "operations.notifications.payment-anomaly-alert.initial-delay=1d",
    "operations.notifications.payment-outbox-alert.enabled=false",
    "commerce.messaging.enabled=false", "operations.jobs.worker.enabled=false", "operations.jobs.planner.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAnomalyAlertHttpPostgresTest {
    private static final String PATH = "/internal/v1/payment-observations/anomalies";
    private static final String LOCK_NAME = "operations-payment-anomaly-alert";
    private static final WireMockServer SERVER = start();

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @Autowired PaymentAnomalyAlertScheduler scheduler;
    @Autowired PaymentAnomalyAlertStateRepository states;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationPushPort streams;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired CircuitBreakerRegistry circuits;
    @MockitoBean NotificationRecipientClient recipients;

    private final ConcurrentLinkedQueue<NotificationPush> received = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    private final CountDownLatch delivered = new CountDownLatch(2);
    private final CountDownLatch extraDelivery = new CountDownLatch(1);
    private NotificationStream firstStream;
    private NotificationStream secondStream;
    private Instant observedAt;

    private static WireMockServer start() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void paymentUrl(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.openfeign.client.config.paymentAnomalyObservationClient.url", SERVER::baseUrl);
    }

    @BeforeEach
    void setup() {
        SERVER.resetAll();
        circuits.circuitBreaker("payment-service").reset();
        notifications.deleteAll();
        states.deleteAll();
        // Preserve the row because JdbcLockProvider caches previously inserted lock names.
        expireLease();
        observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(1);
        when(recipients.page(null, 100)).thenReturn(Response.success(new NotificationRecipientClient.RecipientPage(List.of("7", "8"))));
        firstStream = stream(7);
        secondStream = stream(8);
        streams.register(7, firstStream);
        streams.register(8, secondStream);
    }

    @AfterEach
    void unregister() {
        streams.unregister(7, firstStream);
        streams.unregister(8, secondStream);
    }

    @AfterAll
    static void close() { SERVER.stop(); }

    @Test
    void authenticatedHttpCommitsStateAndNotificationsBeforeSseDespiteCallerRollback() throws Exception {
        respond(observedAt.minusSeconds(1), false);
        scheduler.observe();
        assertThat(states.findAll()).hasSize(5).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(PaymentAnomalyPolicy.Status.NORMAL);
            assertThat(row.getLastObservedAt()).isEqualTo(observedAt.minusSeconds(1));
        });
        assertThat(notifications.count()).isZero();
        assertThat(received).isEmpty();
        assertThat(expireLease()).isEqualTo(1);

        respond(observedAt, true);
        new TransactionTemplate(transactions).executeWithoutResult(outer -> {
            scheduler.observe();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            awaitDelivery();
            outer.setRollbackOnly();
        });

        assertCommitted();
        assertThat(received).hasSize(2).extracting(NotificationPush::notificationId).doesNotHaveDuplicates();
        assertServiceTokens(2);
    }

    @ParameterizedTest(name = "source HTTP {0} preserves committed state (200 has malformed body)")
    @ValueSource(ints = {401, 503, 200})
    void sourceFailurePreservesStateAndNotificationsWithoutAnotherSse(int status) throws Exception {
        respond(observedAt, true);
        scheduler.observe();
        awaitDelivery();
        assertCommitted();
        var stateBefore = stateSnapshot();
        var notificationsBefore = notificationSnapshot();
        var messagesBefore = List.copyOf(received);
        assertThat(expireLease()).isEqualTo(1);
        SERVER.resetAll();
        SERVER.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "application/json").withBody("{invalid-observation")));

        assertThatCode(scheduler::observe).doesNotThrowAnyException();

        assertServiceTokens(1);
        assertThat(stateSnapshot()).isEqualTo(stateBefore);
        assertThat(notificationSnapshot()).isEqualTo(notificationsBefore);
        assertThat(extraDelivery.await(300, TimeUnit.MILLISECONDS)).as("no additional stream delivery after source failure").isFalse();
        assertThat(received).containsExactlyElementsOf(messagesBefore);
        assertThat(failures).isEmpty();
    }

    private NotificationStream stream(long recipientId) {
        return message -> {
            if (delivered.getCount() == 0) { extraDelivery.countDown(); }
            try {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                // A new transaction on the delivery thread must see both state and notification rows.
                var reader = new TransactionTemplate(transactions);
                reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                reader.setReadOnly(true);
                reader.executeWithoutResult(ignored -> {
                    assertCommitted();
                    var row = notifications.findById(Long.parseLong(message.notificationId())).orElseThrow();
                    assertThat(row.getRecipientMemberId()).isEqualTo(recipientId);
                    assertThat(message.kind()).isEqualTo(row.getKind().toMetadata());
                    assertThat(message.severity()).isEqualTo(row.getSeverity().toMetadata());
                    assertThat(message.title()).isEqualTo(row.getTitle());
                    assertThat(message.linkPath()).isEqualTo(row.getLinkPath());
                    assertThat(message.read()).isFalse();
                    // PostgreSQL stores microseconds; the saved entity used by push may retain nanoseconds.
                    assertThat(message.createdAt()).isCloseTo(row.getCreatedAt(), within(1, ChronoUnit.MICROS));
                });
                received.add(message);
            } catch (Throwable failure) {
                // Push is best effort; surface callback assertion failures on the test thread.
                failures.add(failure);
            } finally {
                delivered.countDown();
            }
        };
    }

    private void awaitDelivery() {
        try {
            assertThat(delivered.await(10, TimeUnit.SECONDS)).as("both registered streams received committed notifications").isTrue();
            assertThat(failures).isEmpty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void assertCommitted() {
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(states.findAll()).hasSize(5).allSatisfy(row -> assertThat(row.getLastObservedAt()).isEqualTo(observedAt));
        assertThat(states.findById("UNKNOWN").orElseThrow().getStatus()).isEqualTo(PaymentAnomalyPolicy.Status.ACTIVE);
    }

    private void assertServiceTokens(int expectedRequests) {
        var requests = SERVER.findAll(getRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(expectedRequests);
        var decoder = InternalTokenDecoderFactory.create(TestServiceTokens.properties("payment-service"), Clock.systemUTC());
        requests.forEach(request -> {
            var authorization = request.getHeader("Authorization");
            assertThat(authorization).startsWith("Bearer ");
            var jwt = decoder.decode(authorization.substring(7));
            assertThat(jwt.getAudience()).containsExactly("payment-service");
            assertThat(jwt.getClaimAsString("iss")).isEqualTo("operations-service");
        });
    }

    private int expireLease() {
        return jdbc.update("update operations.shedlock set lock_until = ? where name = ?",
            Timestamp.from(Instant.now().minusSeconds(1)), LOCK_NAME);
    }

    private List<Map<String, Object>> stateSnapshot() {
        return jdbc.queryForList("select * from operations.payment_anomaly_alert_state order by family");
    }

    private List<Map<String, Object>> notificationSnapshot() {
        return jdbc.queryForList("select * from operations.notification order by id");
    }

    private void respond(Instant at, boolean active) {
        String unknown = active ? "{\"count\":1,\"oldestAt\":\"" + at.minusSeconds(600) + "\"}" : "{\"count\":0,\"oldestAt\":null}";
        SERVER.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
            {"dataHeader":{"success":true},"dataBody":{"observedAt":"%s",
            "unknown":%s,"manualReviewUnknown":{"count":0,"oldestAt":null},
            "inProgress":{"count":0,"oldestAt":null},"returnedReady":{"count":0,"oldestAt":null},
            "lateApproved":{"count":0,"oldestAt":null},"doneOrderUnsynced":{"count":0,"oldestAt":null}}}
            """.formatted(at, unknown))));
    }
}
