package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.support.OrderPaymentCrashChild;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real child termination + real order PostgreSQL + parent-owned HTTP fake; no real product DB or payment PG. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"})
@Import(OrderPaymentConsumerH2Test.Config.class)
class OrderPaymentCrashRecoveryPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");
    private static final AtomicLong IDS = new AtomicLong(91_000_000);
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    @Autowired OrderRepositoryPort orders;
    @Autowired OrderPaymentProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void killAfterInitialConfirm200ThenFreshJvmCanceledReplayRestoresExactlyOnce() throws Exception {
        runCrash(false, false, false);
    }

    @Test
    void killAfterReReservedPartialCancelRestore200ThenFreshJvmUsesSameClaim() throws Exception {
        runCrash(true, false, false);
    }

    @Test
    void killSweepJvmAfterProduct200ThenFreshSweepUsesSameClaimWithoutInbox() throws Exception {
        runCrash(true, true, false);
    }

    @Test
    void killLoserAfterConfirmAndKillSweepAfterRestoreThenFreshSweepRecoversWithSameClaim() throws Exception {
        runCrash(true, false, true);
    }

    private void runCrash(boolean reReserved, boolean sweepRecovery, boolean abandoned) throws Exception {
        long orderId = IDS.incrementAndGet();
        long paymentId = IDS.incrementAndGet();
        long original = IDS.incrementAndGet();
        long replacement = IDS.incrementAndGet();
        UUID event = UUID.randomUUID();
        Order order = Order.place(orderId, 7L, domesticQuote(quoteLine(101, 1000, 1)), IDS::incrementAndGet, address("KR"), original, NOW.plusSeconds(1800));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order)));
        if (reReserved) {
            jdbc.update("update orders.orders set status='EXPIRED',version=version+1 where id=?", orderId);
        }
        long firstParticipant = abandoned ? IDS.incrementAndGet() : paymentId;
        if (abandoned) {
            var firstPayment = new VerifiedPayment(firstParticipant, orderId, 7L, order.quote().totalAmount(), NOW, "DONE");
            assertThat(processor.prepareConfirmation(firstPayment, replacement, UUID.randomUUID())).isTrue();
        }
        AtomicReference<String> source = new AtomicReference<>("DONE");
        AtomicBoolean barrier = new AtomicBoolean();
        Set<Long> confirmed = ConcurrentHashMap.newKeySet();
        Set<String> claims = ConcurrentHashMap.newKeySet();
        AtomicInteger applications = new AtomicInteger();
        AtomicInteger restoreRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = "OK";
            if (path.equals("/payment")) { body = source.get(); }
            else if (path.equals("/barrier")) { barrier.set(true); }
            else if (path.equals("/reserve")) { body = Long.toString(replacement); }
            else if (path.startsWith("/confirm/")) {
                long reservation = Long.parseLong(path.substring("/confirm/".length()));
                if (reReserved && reservation == original) { body = "RELEASED"; }
                else { confirmed.add(reservation); }
            } else if (path.startsWith("/release/")) {
                body = confirmed.contains(Long.parseLong(path.substring("/release/".length()))) ? "CONFIRMED" : "RELEASED";
            } else if (path.startsWith("/restore/")) {
                String[] parts = path.split("/");
                if (!parts[3].equals(Long.toString(reReserved ? replacement : original)) || !parts[4].equals("1")) {
                    exchange.sendResponseHeaders(409, -1);
                    exchange.close();
                    return;
                }
                restoreRequests.incrementAndGet();
                if (claims.add(path)) { applications.incrementAndGet(); }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        Path temporary = null;
        Process child = null;
        Process resumed = null;
        try {
            server.start();
            temporary = Files.createTempDirectory("order-c2b-child-");
            String http = "http://127.0.0.1:" + server.getAddress().getPort();
            child = start(temporary, "first", http, orderId, paymentId, event, "confirm");
            Process first = child;
            await().atMost(Duration.ofSeconds(90)).until(() -> barrier.get() || !first.isAlive());
            assertThat(barrier).as("child boundary: %s", diagnostics(temporary.resolve("first.log"))).isTrue();
            assertThat(count("order_payment", "payment_id", paymentId)).isZero();
            var attempt = jdbc.queryForMap("select id, origin, claim_id, stock_reservation_id from orders.order_stock_restoration where payment_id=?",
                firstParticipant);
            assertThat(attempt.get("origin")).isEqualTo("CONFIRMATION_ATTEMPT");
            assertThat(attempt.get("stock_reservation_id")).isEqualTo(reReserved ? replacement : original);
            if (abandoned) {
                // 최초 참여 결제는 반영 기록 없이 멈춘다. 두 번째 결제의 확정과 거절은 서로 다른 처리에서 끝난다.
                assertThat(jdbc.queryForObject("select count(*) from orders.order_stock_confirmation_participant where work_id=? and payment_id=?",
                    Long.class, attempt.get("id"), paymentId)).isEqualTo(1);
                assertThat(count("order_payment", "payment_id", firstParticipant)).isZero();
                var command = new PaymentApprovalCommand(event, paymentId, orderId, 7L, order.quote().totalAmount(), NOW, "CARD");
                var payment = new VerifiedPayment(paymentId, orderId, 7L, command.amount(), NOW, "DONE");
                processor.complete(command, payment, StockConfirmation.stockUnavailable());
                kill(child);
                long claim = (long) attempt.get("claim_id");
                resumed = start(temporary, "classify", http, orderId, paymentId, event, "none", "1000");
                assertSuccess(resumed, temporary.resolve("classify.log"));
                assertThat(jdbc.queryForObject("select origin from orders.order_stock_restoration where claim_id=?", String.class, claim))
                    .isEqualTo("ABANDONED_CONFIRMATION");
                assertThat(restoreRequests).hasValue(0);
                assertThat(jdbc.queryForObject("select count(*) from orders.order_stock_confirmation_participant where work_id=?",
                    Long.class, attempt.get("id"))).isZero();

                barrier.set(false);
                resumed = start(temporary, "sweep-stop", http, orderId, paymentId, event, "restore", "1120");
                Process interrupted = resumed;
                await().atMost(Duration.ofSeconds(90)).until(() -> barrier.get() || !interrupted.isAlive());
                assertThat(barrier).as("sweep boundary: %s", diagnostics(temporary.resolve("sweep-stop.log"))).isTrue();
                assertThat(jdbc.queryForObject("select status from orders.order_stock_restoration where claim_id=?", String.class, claim))
                    .isEqualTo("RESTORE_PENDING");
                kill(resumed);

                resumed = start(temporary, "sweep-resume", http, orderId, paymentId, event, "none", "1360");
                assertSuccess(resumed, temporary.resolve("sweep-resume.log"));
                assertThat(jdbc.queryForObject("select status from orders.order_stock_restoration where claim_id=?", String.class, claim))
                    .isEqualTo("RESTORED");
                assertThat(restoreRequests).hasValue(2);
                assertThat(applications).hasValue(1);
                assertThat(claims).containsExactly("/restore/" + claim + "/" + replacement + "/1");
                assertThat(row(paymentId).get("result")).isEqualTo("REJECTED_STOCK_UNAVAILABLE");
                assertThat(row(paymentId).get("restored_at")).isNull();
                assertThat(inbox(event)).isEqualTo(1);
                assertThat(outbox(orderId)).isEqualTo(1);
                return;
            }
            if (reReserved) {
                assertThat(jdbc.queryForObject("select stock_reservation_id from orders.order_stock_confirmation where payment_id=?",
                    Long.class, paymentId)).isEqualTo(replacement);
            }
            kill(child);
            assertThat(confirmed).contains(reReserved ? replacement : original);
            source.set(reReserved ? "PARTIAL_CANCELED" : "CANCELED");
            barrier.set(false);
            resumed = start(temporary, "second", http, orderId, paymentId, event, reReserved ? "restore" : "none");
            if (reReserved) {
                Process second = resumed;
                await().atMost(Duration.ofSeconds(90)).until(() -> barrier.get() || !second.isAlive());
                assertThat(barrier).as("child boundary: %s", diagnostics(temporary.resolve("second.log"))).isTrue();
                var pending = row(paymentId);
                assertThat(pending.get("restoration_claim_id")).isEqualTo(attempt.get("claim_id"));
                assertThat(pending.get("restored_at")).isNull();
                var pendingWork = jdbc.queryForMap("select claim_id, stock_reservation_id, status from orders.order_stock_restoration where payment_id=?", paymentId);
                assertThat(pendingWork.get("claim_id")).isEqualTo(pending.get("restoration_claim_id"));
                assertThat(pendingWork.get("stock_reservation_id")).isEqualTo(replacement);
                assertThat(pendingWork.get("status")).isEqualTo("RESTORE_PENDING");
                assertThat(inbox(event)).isZero();
                assertThat(outbox(orderId)).isZero();
                kill(resumed);
                if (sweepRecovery) {
                    barrier.set(false);
                    resumed = start(temporary, "sweep-stop", http, orderId, paymentId, event, "restore", "1000");
                    Process interruptedSweep = resumed;
                    await().atMost(Duration.ofSeconds(90)).until(() -> barrier.get() || !interruptedSweep.isAlive());
                    assertThat(barrier).as("sweep boundary: %s", diagnostics(temporary.resolve("sweep-stop.log"))).isTrue();
                    assertThat(jdbc.queryForObject("select attempts from orders.order_stock_restoration where payment_id=?",
                        Integer.class, paymentId)).isEqualTo(1);
                    assertThat(row(paymentId).get("restored_at")).isNull();
                    assertThat(inbox(event)).isZero();
                    assertThat(outbox(orderId)).isZero();
                    kill(resumed);
                    resumed = start(temporary, "sweep-resume", http, orderId, paymentId, event, "none", "1120");
                } else {
                    resumed = start(temporary, "third", http, orderId, paymentId, event, "none");
                }
                assertSuccess(resumed, temporary.resolve(sweepRecovery ? "sweep-resume.log" : "third.log"));
                assertThat(row(paymentId).get("restoration_claim_id")).isEqualTo(pending.get("restoration_claim_id"));
                assertThat(restoreRequests).hasValue(sweepRecovery ? 3 : 2);
            } else {
                assertSuccess(resumed, temporary.resolve("second.log"));
                assertThat(restoreRequests).hasValue(1);
            }
            assertThat(row(paymentId).get("result")).isEqualTo("REJECTED_PAYMENT_NOT_DONE");
            assertThat(row(paymentId).get("restored_at")).isNotNull();
            assertThat(count("order_stock_restoration", "payment_id", paymentId)).isEqualTo(1);
            var completedWork = jdbc.queryForMap("select claim_id, status, restored_at from orders.order_stock_restoration where payment_id=?", paymentId);
            assertThat(completedWork.get("claim_id")).isEqualTo(attempt.get("claim_id")).isEqualTo(row(paymentId).get("restoration_claim_id"));
            assertThat(completedWork.get("status")).isEqualTo("RESTORED");
            assertThat(completedWork.get("restored_at")).isEqualTo(row(paymentId).get("restored_at"));
            assertThat(applications).hasValue(1);
            assertThat(inbox(event)).isEqualTo(sweepRecovery ? 0 : 1);
            assertThat(outbox(orderId)).isEqualTo(reReserved ? 1 : 0);
            resumed = start(temporary, "duplicate", http, orderId, paymentId, UUID.randomUUID(), "none");
            assertSuccess(resumed, temporary.resolve("duplicate.log"));
            assertThat(applications).hasValue(1);
            assertThat(outbox(orderId)).isEqualTo(reReserved ? 1 : 0);
        } finally {
            try {
                if (child != null && child.isAlive()) { kill(child); }
            } finally {
                try {
                    if (resumed != null && resumed.isAlive()) { kill(resumed); }
                } finally {
                    try {
                        server.stop(0);
                    } finally {
                        // Delete only files owned by this test. Never print raw child logs or credentials.
                        if (temporary != null) {
                            try (var files = Files.list(temporary)) {
                                for (Path file : files.toList()) { Files.deleteIfExists(file); }
                            }
                            Files.deleteIfExists(temporary);
                        }
                    }
                }
            }
        }
    }

    private Process start(Path directory, String run, String http, long order, long payment, UUID event, String stage) throws Exception {
        return start(directory, run, http, order, payment, event, stage, null);
    }

    private Process start(Path directory, String run, String http, long order, long payment, UUID event, String stage, String sweepSeconds) throws Exception {
        Path arguments = directory.resolve(run + ".args");
        Path classpathFile = Path.of(Objects.requireNonNull(System.getProperty("order.test.runtimeClasspathFile"), "test runtime classpath file property"));
        // Gradle의 원본 파일은 UTF-8, Java launcher의 임시 argfile은 native 인코딩이다. 미지원 문자는 치환 없이 실패한다.
        String classpath = Files.readString(classpathFile, StandardCharsets.UTF_8);
        if (classpath.indexOf('\n') >= 0 || classpath.indexOf('\r') >= 0) {
            throw new IllegalStateException("child classpath file must contain one line");
        }
        String escapedClasspath = classpath.replace("\\", "/").replace("\"", "\\\"");
        Charset launcherCharset = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        String launcherArguments = "-cp\n\"" + escapedClasspath + "\"\n" + OrderPaymentCrashChild.class.getName();
        try {
            var encoded = launcherCharset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(launcherArguments));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            Files.write(arguments, bytes);
        } catch (CharacterCodingException exception) {
            // 원문 클래스패스·환경값·인코더 예외 메시지는 출력하지 않는다.
            throw new IllegalStateException("child launcher argfile encoding unsupported");
        }
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve(run + ".log").toFile());
        var env = builder.environment();
        env.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        env.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "validate");
        env.put("SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA", "orders");
        env.put("SPRING_CLOUD_DISCOVERY_ENABLED", "false");
        env.put("EUREKA_CLIENT_ENABLED", "false");
        env.put("COMMERCE_MESSAGING_ENABLED", "false");
        env.put("LOGGING_LEVEL_ROOT", "ERROR");
        env.put("CASE_HTTP", http); env.put("CASE_ORDER", Long.toString(order)); env.put("CASE_PAYMENT", Long.toString(payment));
        env.put("CASE_EVENT", event.toString()); env.put("CASE_STOP", stage);
        if (sweepSeconds != null) { env.put("CASE_SWEEP_SECONDS", sweepSeconds); }
        return builder.start();
    }

    private static String diagnostics(Path log) throws Exception {
        // Only class names/stack frame locations are included; never exception messages or configuration values.
        try (var lines = Files.lines(log)) {
            return lines.filter(line -> line.matches("\\s*at [a-zA-Z0-9_.$]+\\([^)]*:[0-9]+\\)"))
                .limit(15).reduce("", (left, right) -> left + " " + right.trim());
        }
    }

    private static void kill(Process process) throws Exception {
        long pid = process.pid();
        process.destroyForcibly();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).as("Owned child PID %s terminated", pid).isFalse();
        assertThat(process.exitValue()).isNotZero();
    }

    private static void assertSuccess(Process process, Path log) throws Exception {
        boolean completed = process.waitFor(90, TimeUnit.SECONDS);
        assertThat(completed).as("Fresh child completed: %s", diagnostics(log)).isTrue();
        assertThat(process.exitValue()).as("Fresh child exit: %s", diagnostics(log)).isZero();
    }

    private Map<String, Object> row(long payment) { return jdbc.queryForMap("select * from orders.order_payment where payment_id=?", payment); }
    private long count(String table, String column, Object id) {
        return jdbc.queryForObject("select count(*) from orders." + table + " where " + column + "=?", Long.class, id);
    }
    private long inbox(UUID event) { return count("processed_event", "event_id", event); }
    private long outbox(long order) { return count("outbox", "aggregate_id", Long.toString(order)); }
}
