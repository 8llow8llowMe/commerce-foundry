package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentWebhookInboxAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom.PaymentWebhookInboxCustomRepositoryImpl;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookConflictException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentWebhookInboxPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.Reason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.State;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment"})
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentWebhookInboxAdapter.class,
    PaymentWebhookInboxCustomRepositoryImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentWebhookPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    @Autowired PaymentWebhookInboxPort inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @AfterEach void clean() { jdbc.update("delete from payment.payment_webhook_inbox"); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private PaymentWebhookInbox save(long id, Instant time) {
        return tx().execute(status -> inbox.insert(PaymentWebhookInbox.received(id, 900000001L, String.valueOf(id).repeat(64).substring(0, 64), time)));
    }

    @Test void dueOrderBudgetIndexAndCheckConstraintsAreActualPostgresContracts() {
        save(1, NOW); save(2, NOW.minusSeconds(1)); save(3, NOW.plusSeconds(1));
        var due = tx().execute(status -> inbox.findDue(NOW, 1));
        assertThat(due).extracting(PaymentWebhookInbox::id).containsExactly(2L);
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname='payment' "
            + "and indexname='idx_payment_webhook_inbox_state_available_at_id'", String.class)).contains("(state, available_at, id)");
        assertThatThrownBy(() -> jdbc.update("update payment.payment_webhook_inbox set attempts=-1 where id=1"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update payment.payment_webhook_inbox set state='PROCESSING' where id=1"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void twoClaimersHaveExactlyOneWinnerAndExpiredOwnerCannotCommit() throws Exception {
        var row = save(1, NOW);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    tx().execute(status -> inbox.claim(row, row.claim(java.util.UUID.randomUUID().toString(), NOW, Duration.ofSeconds(60)), NOW));
                    return true;
                } catch (PaymentWebhookConflictException lost) { return false; }
            };
            var first = pool.submit(claim); var second = pool.submit(claim);
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        var old = tx().execute(status -> inbox.findById(1).orElseThrow());
        var fresh = tx().execute(status -> inbox.claim(old, old.claim("new-owner", NOW.plusSeconds(61), Duration.ofSeconds(60)), NOW.plusSeconds(61)));
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> inbox.finish(old,
            old.finish(State.COMPLETED, Reason.NONE, NOW.plusSeconds(62), NOW.plusSeconds(62)), NOW.plusSeconds(62))))
            .isInstanceOf(PaymentWebhookConflictException.class);
        tx().executeWithoutResult(status -> inbox.finish(fresh,
            fresh.finish(State.COMPLETED, Reason.NONE, NOW.plusSeconds(62), NOW.plusSeconds(62)), NOW.plusSeconds(62)));
        var done = tx().execute(status -> inbox.findById(1).orElseThrow());
        assertThat(done.state()).isEqualTo(State.COMPLETED);
        assertThat(done.attempts()).isEqualTo(2);
    }

    @Test void concurrentDuplicateFingerprintLosesThenReadsWinnerInFreshTransaction() throws Exception {
        String fingerprint = "a".repeat(64);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.function.LongFunction<java.util.concurrent.Callable<Boolean>> insert = id -> () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    tx().executeWithoutResult(status -> inbox.insert(PaymentWebhookInbox.received(id, 900000001L, fingerprint, NOW)));
                    return true;
                } catch (PaymentWebhookConflictException duplicate) {
                    // PostgreSQL의 unique 충돌로 중단된 TX는 이미 종료됐다. 새 TX에서만 승자의 커밋을 확인한다.
                    var winner = tx().execute(status -> inbox.findByFingerprint(fingerprint));
                    assertThat(winner).isPresent();
                    return false;
                }
            };
            var first = pool.submit(insert.apply(1));
            var second = pool.submit(insert.apply(2));
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("select count(*) from payment.payment_webhook_inbox", Long.class)).isOne();
    }

    @Test void exactAdditiveOperationalScriptInstallsInboxOnExistingPaymentSchema() throws Exception {
        jdbc.execute("drop table payment.payment_webhook_inbox");
        // 기존 payment/outbox 스키마를 유지한 상태로 운영 파일 전체(UTF8/BEGIN/COMMIT 포함)를 실제 실행한다.
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("ddl/payment-webhook-inbox.sql"), StandardCharsets.UTF_8));
        }
        var inserted = save(1, NOW);
        assertThat(inserted.state()).isEqualTo(State.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from payment.payment", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment.outbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
            select i.indisvalid and i.indisready from pg_index i join pg_class c on c.oid=i.indexrelid
             join pg_namespace n on n.oid=c.relnamespace
             where n.nspname='payment' and c.relname='idx_payment_webhook_inbox_state_available_at_id'
            """, Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("update payment.payment_webhook_inbox set attempts=-1 where id=1"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update payment.payment_webhook_inbox set state='PROCESSING' where id=1"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void nanosecondLeaseAndRetryTimestampsRoundTripToStoredMicroseconds() {
        var original = save(1, NOW.plusNanos(123));
        var claimed = tx().execute(status -> inbox.claim(original, original.claim("owner", NOW, Duration.ofSeconds(60).plusNanos(999)), NOW));
        var loaded = tx().execute(status -> inbox.findById(1).orElseThrow());
        assertThat(loaded).isEqualTo(claimed);
        var retry = claimed.finish(State.RETRY, Reason.PG_RETRY, NOW.plusSeconds(5).plusNanos(999), NOW.plusNanos(999));
        tx().executeWithoutResult(status -> inbox.finish(claimed, retry, NOW));
        var stored = tx().execute(status -> inbox.findById(1).orElseThrow());
        assertThat(stored.availableAt()).isEqualTo(retry.availableAt());
        assertThat(stored.updatedAt()).isEqualTo(retry.updatedAt());
    }
}
