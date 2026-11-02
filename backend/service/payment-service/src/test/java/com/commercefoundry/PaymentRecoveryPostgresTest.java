package com.commercefoundry;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentRecoveryQueryAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentRepositoryAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom.PaymentRecoveryCustomRepositoryImpl;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRecoveryQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentInquiry;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
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

/** PostgreSQL 질의·운영 DDL 계약. Docker 없는 로컬은 skip, CI는 반드시 실제 실행한다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentRepositoryAdapter.class, PaymentMapperImpl.class,
    PaymentRecoveryQueryAdapter.class, PaymentRecoveryCustomRepositoryImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentRecoveryPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentRepositoryPort repository;
    @Autowired PaymentRecoveryQueryPort queries;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void actualQueryAndRollbackKeepEligibilityAndCompositeCursor() {
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            repository.save(unknown());
            repository.save(ready(PAYMENT_ID + 1, ORDER_ID + 1).startApproval("key1", "00000000-0000-4000-8000-000000000001", STARTED_AT)
                .markUnknown(PaymentUnknownReason.TIMEOUT));
            var first = queries.findCandidates(PaymentStatus.UNKNOWN, STARTED_AT, null, 1);
            assertThat(first).singleElement().satisfies(candidate -> assertThat(candidate.id()).isEqualTo(PAYMENT_ID));
            assertThat(queries.findCandidates(PaymentStatus.UNKNOWN, STARTED_AT, first.getFirst(), 10))
                .singleElement().satisfies(candidate -> assertThat(candidate.id()).isEqualTo(PAYMENT_ID + 1));
            status.setRollbackOnly();
        });
        assertThat(queries.findCandidates(PaymentStatus.UNKNOWN, STARTED_AT, null, 10)).isEmpty();
    }

    @Test
    void arbitraryOldEnumCheckMigratesAtomicallyPreservesCrossChecksAndRerunIsNoop() throws Exception {
        String check = jdbc.queryForObject("""
            select c.conname from pg_constraint c join pg_attribute a
              on a.attrelid=c.conrelid and a.attname='abort_reason'
             where c.conrelid='payment.payment'::regclass and c.contype='c' and c.conkey=array[a.attnum]::smallint[]
            """, String.class);
        jdbc.execute("alter table payment.payment drop constraint \"" + check + "\"");
        jdbc.execute("alter table payment.payment add constraint arbitrary_legacy_abort_check check "
            + "(abort_reason in ('ADMIN','AMOUNT_CHANGED','ORDER_EXPIRED','REPREPARED'))");
        String migration = resource("payment-recovery-abort-reason.sql");
        jdbc.execute(migration); // 달러 인용 DO 전체를 한 문장으로 보낸다. 스크립트 파서로 분할하지 않는다.
        jdbc.execute(migration);
        var saved = new TransactionTemplate(transactions).execute(status -> repository.save(reverted()
            .abortAfterInquiry(new PaymentInquiry.Failed(PAYMENT_KEY, "EXPIRED", null), PaymentAbortReason.RECOVERY_NOT_APPROVED)));
        try {
            assertThat(saved.abortReason()).isEqualTo(PaymentAbortReason.RECOVERY_NOT_APPROVED);
            assertThatThrownBy(() -> jdbc.update("update payment.payment set abort_reason=null where id=?", saved.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.execute("drop index payment.idx_payment_status_approval_started_at_id");
            jdbc.execute(resource("payment-recovery-index.sql")); // NOT_SUPPORTED + autocommit에서 실제 인덱스를 새로 만든다.
            assertThat(jdbc.queryForObject("""
                select i.indisvalid and i.indisready from pg_index i join pg_class c on c.oid=i.indexrelid
                 join pg_namespace n on n.oid=c.relnamespace
                 where n.nspname='payment' and c.relname='idx_payment_status_approval_started_at_id'
                """, Boolean.class)).isTrue();
        } finally {
            jdbc.update("delete from payment.payment");
        }
    }

    @Test
    void unexpectedEnumDefinitionAbortsWithoutDroppingIt() throws Exception {
        String check = jdbc.queryForObject("""
            select c.conname from pg_constraint c join pg_attribute a on a.attrelid=c.conrelid and a.attname='abort_reason'
             where c.conrelid='payment.payment'::regclass and c.contype='c' and c.conkey=array[a.attnum]::smallint[]
            """, String.class);
        jdbc.execute("alter table payment.payment drop constraint \"" + check + "\"");
        jdbc.execute("alter table payment.payment add constraint unexpected_abort_values check (abort_reason in ('ADMIN'))");
        try {
            assertThatThrownBy(() -> jdbc.execute(resource("payment-recovery-abort-reason.sql")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='payment.payment'::regclass "
                + "and conname='unexpected_abort_values'", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("alter table payment.payment drop constraint unexpected_abort_values");
            jdbc.execute("alter table payment.payment add constraint ck_payment_abort_reason_values check "
                + "(abort_reason in ('ADMIN','AMOUNT_CHANGED','ORDER_EXPIRED','RECOVERY_NOT_APPROVED','REPREPARED'))");
        }
    }

    private static String resource(String name) throws Exception {
        try (var input = PaymentRecoveryPostgresTest.class.getResourceAsStream("/ddl/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
