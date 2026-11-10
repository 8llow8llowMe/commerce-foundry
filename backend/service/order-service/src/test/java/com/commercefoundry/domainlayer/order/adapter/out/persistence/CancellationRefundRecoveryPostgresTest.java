package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.commercefoundry.domainlayer.order.adapter.out.client.PaymentCancelClientAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.client.StockRestorationClientAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.PaymentCancelClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.StockRestorationClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.PaymentCancelInternalResponseSupport;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.StockReservationInternalResponseSupport;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.CancellationStepOutcome;
import com.commercefoundry.domainlayer.order.application.port.out.*;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.*;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.support.OrderReturnTestConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.persistence.EntityManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL 독립 TX와 실제 Feign/adapter/loopback HTTP 경계다. fake 원장은 실제 PG 환불 증거가 아니다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"})
@Import({OrderReturnTestConfig.class, CancellationRefundRecoveryPostgresTest.HttpClients.class})
class CancellationRefundRecoveryPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong IDS = new AtomicLong(97_000_000);
    private static final Instant NOW = Instant.parse("2000-01-01T00:00:00Z");
    private static final ExecutorService THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final Map<String, String> SUCCEEDED = new ConcurrentHashMap<>();
    private static final AtomicInteger RESTORES = new AtomicInteger();
    private static final AtomicBoolean DROP = new AtomicBoolean();
    private static volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);
    private static HttpServer server;
    @Autowired OrderRepositoryPort orders;
    @Autowired ClaimRepositoryPort claims;
    @Autowired OrderCancellationPipelineProcessor pipeline;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean ClaimRepositoryAdapter adapter;
    @MockitoSpyBean OrderCancellationProcessor processor;
    private final AtomicInteger conflicts = new AtomicInteger();
    private final AtomicBoolean failCommit = new AtomicBoolean();
    private final AtomicBoolean failFlush = new AtomicBoolean();
    private final AtomicBoolean failAfterCommit = new AtomicBoolean();
    private Runnable beforeFallback = () -> { };

    @BeforeEach
    void resetFaults() {
        ClaimRepositoryAdapter adapterSpy = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(adapter);
        OrderCancellationProcessor processorSpy = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(processor);
        REQUESTS.clear(); SUCCEEDED.clear(); RESTORES.set(0); DROP.set(false);
        entered = new CountDownLatch(0); release = new CountDownLatch(0);
        doAnswer(call -> {
            Claim candidate = call.getArgument(0);
            if (candidate.status() == Claim.Status.REFUNDED && conflicts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                // 실제 별도 트랜잭션이 먼저 커밋해 두 번의 낙관적 UPDATE를 패배시킨다.
                THREADS.submit(() -> tx().executeWithoutResult(status -> {
                    Claim current = read(candidate);
                    claims.update(current.holdUntil(Instant.now().plusSeconds(60)));
                })).get(10, TimeUnit.SECONDS);
            }
            Object result = call.callRealMethod();
            if (candidate.status() == Claim.Status.REFUNDED && failFlush.getAndSet(false)) {
                jdbc.update("update orders.claim set attempt_count=-1 where id=?", candidate.id());
            }
            if (candidate.status() == Claim.Status.REFUNDED && failCommit.getAndSet(false)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("injected commit failure"); }
                });
            }
            if (candidate.status() == Claim.Status.REFUNDED && failAfterCommit.getAndSet(false)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { throw new IllegalStateException("injected post-commit observation failure"); }
                });
            }
            return result;
        }).when(adapterSpy).update(any());
        doAnswer(call -> { beforeFallback.run(); return call.callRealMethod(); }).when(processorSpy).recordConfirmedRefundWithLock(any());
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private Claim read(Claim source) { return claims.findByOrderIdAndRequestKey(source.orderId(), source.requestKey()).orElseThrow(); }
    private Claim seed() {
        long id = IDS.incrementAndGet();
        Order order = Order.place(id, 7, domesticQuote(quoteLine(101, 1_000, 2)), IDS::incrementAndGet, address("KR"), IDS.incrementAndGet(), NOW);
        return tx().execute(status -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            jdbc.update("update orders.orders set status='CANCEL_REQUESTED', version=version+1 where id=?", id);
            entityManager.clear();
            Order saved = orders.findById(id).orElseThrow();
            return claims.insert(Claim.requestCancellation(IDS.incrementAndGet(), saved, 77, saved.quote().totalAmount(), OrderCancelReason.OTHER,
                IDS::incrementAndGet, NOW, Duration.ofMinutes(2)));
        });
    }

    @Test
    void exhaustedCasFallsBackToFreshLockedTransactionAndPreservesLateSuccess() {
        Claim source = seed(); conflicts.set(2);
        Claim result = pipeline.progress(source);
        assertThat(result.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(read(source).status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(orders.findById(source.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(RESTORES).hasValue(1);
        assertThat(REQUESTS).hasSize(2);
    }

    @Test
    void failureThenSuccessKeepsSuccessAndOldFailureCannotChangeNewPhase() throws Exception {
        Claim source = seed(); entered = new CountDownLatch(1); release = new CountDownLatch(1);
        Future<Claim> work = THREADS.submit(() -> pipeline.progress(source));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            // HTTP 대기 중 독립 TX에서 두 행 모두 NOWAIT 잠금 가능: 원격 I/O 동안 DB 잠금을 보유하지 않는다.
            tx().executeWithoutResult(status -> {
                claims.findByIdForUpdate(source.id()).orElseThrow();
                orders.findByIdAndMemberIdForUpdate(source.orderId(), 7).orElseThrow();
            });
            processor.recordAttemptFailure(source);
        } finally { release.countDown(); }
        assertThat(work.get(15, TimeUnit.SECONDS).status()).isEqualTo(Claim.Status.COMPLETED);
        Claim completed = read(source);
        processor.recordAttemptFailure(source);
        assertThat(read(source)).isEqualTo(completed);
        assertThat(RESTORES).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REFUNDED", "RESTORATION_FAILED", "COMPLETED"})
    void alreadyAdvancedSuccessDoesNotReenterRestoration(Claim.Status target) throws Exception {
        Claim source = seed(); entered = new CountDownLatch(1); release = new CountDownLatch(1);
        Future<Claim> work = THREADS.submit(() -> pipeline.progress(source));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Claim confirmed = processor.recordRefund(source, CancellationStepOutcome.done());
            if (target != Claim.Status.REFUNDED) {
                processor.recordRestoration(confirmed, target == Claim.Status.COMPLETED
                    ? CancellationStepOutcome.done() : CancellationStepOutcome.failed("PRODUCT_028"));
            }
            processor.recordAttemptFailure(source);
        } finally { release.countDown(); }
        work.get(15, TimeUnit.SECONDS);
        assertThat(read(source).status()).isEqualTo(target);
        assertThat(RESTORES).hasValue(0);
    }

    @Test
    void anotherFailureCodeCannotDiscardKnownSuccess() throws Exception {
        Claim source = seed(); entered = new CountDownLatch(1); release = new CountDownLatch(1);
        Future<Claim> work = THREADS.submit(() -> pipeline.progress(source));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            processor.recordRefund(source, CancellationStepOutcome.failed("PAYMENT_019"));
        } finally { release.countDown(); }
        assertThat(work.get(15, TimeUnit.SECONDS).status()).isEqualTo(Claim.Status.COMPLETED);
    }

    @Test
    void lockedBoundaryDoesNotReenterRestorationWhenAnotherWorkerWinsAfterBothCasConflicts() {
        Claim source = seed(); conflicts.set(2);
        beforeFallback = () -> {
            try { THREADS.submit(() -> processor.recordRefund(read(source), CancellationStepOutcome.done())).get(10, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        pipeline.progress(source);
        assertThat(read(source).status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(RESTORES).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED"})
    void oldFailureCannotChangeNewLeaseOrPhase(Claim.Status phase) {
        Claim source = seed();
        Claim current = tx().execute(status -> claims.update(phase == Claim.Status.REQUESTED
            ? source.holdUntil(Instant.now().plusSeconds(60)) : source.markRefunded(Instant.now())));
        processor.recordAttemptFailure(source);
        assertThat(read(source)).isEqualTo(current);
    }

    @Test
    void responseLossUsesSameRemoteClaimAndNeverRetriesInsideOneFeignCall() {
        Claim source = seed(); DROP.set(true);
        assertThat(pipeline.progress(source).status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(REQUESTS).hasSize(1);
        assertThat(SUCCEEDED).hasSize(1);
        Claim current = read(source);
        assertThat(pipeline.progress(current).status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(REQUESTS.subList(0, 2)).containsExactly(REQUESTS.getFirst(), REQUESTS.getFirst());
        assertThat(SUCCEEDED).hasSize(1);
        assertThat(RESTORES).hasValue(1);
    }

    enum Failure { FLUSH, COMMIT }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void fallbackFlushOrCommitFailureRollsBackBothRowsThenSameRemoteSuccessCanBeRecovered(Failure failure) {
        Claim source = seed(); conflicts.set(2);
        failCommit.set(failure == Failure.COMMIT); failFlush.set(failure == Failure.FLUSH);
        assertUnavailable(() -> pipeline.progress(source));
        assertThat(read(source).status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(orders.findById(source.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(RESTORES).hasValue(0);
        assertThat(SUCCEEDED).hasSize(1);
        assertThat(pipeline.progress(read(source)).status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(SUCCEEDED).hasSize(1);
    }

    @Test
    void postCommitObservationFailurePreservesSuccessAndNextRestorationDoesNotRefundAgain() {
        Claim source = seed(); conflicts.set(2); failAfterCommit.set(true);
        // 실제 DB commit 뒤 afterCommit 관측 예외를 모의한다. DB wire의 COMMIT 응답 유실 실증은 아니다.
        assertUnavailable(() -> pipeline.progress(source));
        assertThat(read(source).status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(orders.findById(source.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(RESTORES).hasValue(0);
        Claim held = processor.hold(read(source));
        assertThat(pipeline.progress(held).status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(REQUESTS.stream().filter(request -> !request.startsWith("restore|")).count()).isEqualTo(1);
        assertThat(RESTORES).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED"})
    void fallbackClaimOrOrderNowaitConflictIsExplicitWithoutPartialUpdate(Claim.Status lockTarget) throws Exception {
        Claim source = seed(); conflicts.set(2);
        CountDownLatch locked = new CountDownLatch(1), unlock = new CountDownLatch(1);
        AtomicReference<Future<?>> locker = new AtomicReference<>();
        beforeFallback = () -> {
            locker.set(THREADS.submit(() -> tx().executeWithoutResult(status -> {
                if (lockTarget == Claim.Status.REQUESTED) { claims.findByIdForUpdate(source.id()).orElseThrow(); }
                else { orders.findByIdAndMemberIdForUpdate(source.orderId(), 7).orElseThrow(); }
                locked.countDown(); await(unlock);
            })));
            await(locked);
        };
        try {
            OrderException failure = assertUnavailable(() -> pipeline.progress(source));
            Throwable cause = failure;
            while (cause != null && !(cause instanceof java.sql.SQLException)) { cause = cause.getCause(); }
            assertThat(cause).isInstanceOfSatisfying(java.sql.SQLException.class,
                sql -> assertThat(sql.getSQLState()).isEqualTo("55P03"));
        }
        finally { unlock.countDown(); if (locker.get() != null) { locker.get().get(10, TimeUnit.SECONDS); } }
        assertThat(read(source).status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(orders.findById(source.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(RESTORES).hasValue(0);
    }

    @Test
    void lockedRefreshSeesLatestVersionAndRejectsChangedFingerprint() throws Exception {
        Claim source = seed();
        assertThatThrownBy(() -> claims.update(source)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> claims.findByIdForUpdate(source.id())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        tx().executeWithoutResult(status -> {
            entityManager.find(com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimEntity.class, source.id());
            try { THREADS.submit(() -> tx().executeWithoutResult(other -> claims.update(source.holdUntil(Instant.now())))).get(10, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            assertThat(claims.findByIdForUpdate(source.id()).orElseThrow().version()).isEqualTo(source.version() + 1);
        });
        Claim changed = new Claim(source.id(), source.orderId(), source.requestKey(), source.type(), source.status(), source.expectedRefund(), 78L,
            source.attemptCount(), source.nextAttemptAt(), source.failureCode(), source.items(), source.requestedAt(), source.version());
        assertThatThrownBy(() -> processor.recordConfirmedRefundWithLock(changed)).isInstanceOf(IllegalStateException.class);
        assertThat(read(source).status()).isEqualTo(Claim.Status.REQUESTED);
    }

    private static OrderException assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        AtomicReference<OrderException> failure = new AtomicReference<>();
        assertThatThrownBy(action).isInstanceOfSatisfying(OrderException.class, caught -> {
            assertThat(caught.getErrorCode()).isEqualTo(OrderErrorCode.CANCELLATION_REFUND_RECORDING_UNAVAILABLE);
            failure.set(caught);
        });
        return failure.get();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("latch timeout"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    private static synchronized HttpServer server() {
        if (server != null) { return server; }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/", exchange -> {
                var body = JSON.readTree(exchange.getRequestBody());
                String response;
                if (exchange.getRequestURI().getPath().contains("payments")) {
                    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    String fingerprint = exchange.getRequestURI() + "|" + key + "|" + body;
                    REQUESTS.add(fingerprint);
                    assertThat(key).isEqualTo(body.path("claimId").asText());
                    SUCCEEDED.putIfAbsent(key, fingerprint);
                    assertThat(SUCCEEDED.get(key)).isEqualTo(fingerprint);
                    entered.countDown(); await(release);
                    if (DROP.getAndSet(false)) {
                        // 성공을 기억한 뒤 응답 본문만 끊는다. 헤더 전 EOF에 대한 JDK 내부 POST 재전송과 구분한다.
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write('{');
                        exchange.close(); return;
                    }
                    response = "{\"paymentId\":\"77\",\"claimId\":\"" + key + "\",\"status\":\"CANCELED\",\"canceledAmount\":" + body.path("amount").asLong() + "}";
                } else {
                    RESTORES.incrementAndGet(); REQUESTS.add("restore|" + body);
                    String items = java.util.stream.StreamSupport.stream(body.path("items").spliterator(), false)
                        .map(item -> "{\"skuId\":\"" + item.path("skuId").asText() + "\",\"quantity\":" + item.path("quantity").asInt() + ",\"restored\":true}")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
                    response = "{\"restorationId\":\"1\",\"claimId\":\"" + body.path("claimId").asText() + "\",\"reservationId\":\""
                        + body.path("reservationId").asText() + "\",\"reason\":\"CANCELLATION\",\"items\":" + items + "}";
                }
                byte[] bytes = ("{\"dataHeader\":{\"success\":true,\"resultCode\":null,\"resultMessage\":null,\"fieldErrors\":null},\"dataBody\":"
                    + response + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.setExecutor(THREADS); server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    @AfterAll static void stop() { if (server != null) { server.stop(0); } THREADS.shutdownNow(); }

    @TestConfiguration
    static class HttpClients {
        private static <T> T client(Class<T> type) {
            return Feign.builder().contract(new SpringMvcContract()).retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(1, TimeUnit.SECONDS, 10, TimeUnit.SECONDS, true))
                .encoder((body, bodyType, template) -> template.body(JSON.writeValueAsBytes(body), StandardCharsets.UTF_8))
                .decoder((response, bodyType) -> JSON.readValue(response.body().asInputStream(), JSON.constructType(bodyType)))
                .target(type, "http://127.0.0.1:" + server().getAddress().getPort());
        }
        @Bean @Primary PaymentCancelPort httpPayment() {
            return new PaymentCancelClientAdapter(client(PaymentCancelClient.class), new PaymentCancelInternalResponseSupport(
                CircuitBreakerRegistry.ofDefaults(), JSON));
        }
        @Bean @Primary StockRestorationPort httpRestoration() {
            return new StockRestorationClientAdapter(client(StockRestorationClient.class), new StockReservationInternalResponseSupport(
                CircuitBreakerRegistry.ofDefaults(), JSON));
        }
    }
}
