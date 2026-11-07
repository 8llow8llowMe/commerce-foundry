package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.*;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.AutoCancelCursorEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentAutoCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.global.config.PaymentAutoCancelSchemaGuard;
import com.commercefoundry.global.config.PaymentOrderReconciliationSchemaGuard;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Corrected CORE schema -> exact scheduler DDL -> validate, preserving #591's exact-two-seed guard. */
@Testcontainers
@EnabledWithDockerOrOnCi
class PaymentAutoCancelSchedulerUpgradePostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    DataSource source;
    JdbcTemplate jdbc;
    List<Map<String, Object>> oldCursors;
    List<Map<String, Object>> oldLedger;
    List<Map<String, Object>> oldIntents;
    @BeforeEach
    void oldSchema() throws Exception {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("drop schema if exists payment cascade");
        jdbc.execute(resource("payment-schema-pre-auto-cancel-scheduler.sql"));
        jdbc.update("insert into payment.order_reconciliation_cursor(lane,created_at,updated_at,approved_before,cycle_upper_id,last_id,version)"
            + " values ('RECONCILE',now(),now(),now(),100,10,7),('REJECTED',now(),now(),now(),200,20,8)");
        jdbc.update("insert into payment.payment_cancel(claim_id,created_at,updated_at,payment_id,amount,currency,status,version)"
            + " values(123,now(),now(),9000000001,129000,'KRW','PENDING',0)");
        jdbc.update("insert into payment.payment_auto_cancel(payment_id,claim_id,created_at,updated_at,pg_key,amount,currency,state,attempts,"
            + "next_attempt_at,first_attempt_at,lease_token,lease_until,version) values(9000000001,123,now(),now(),"
            + "'payment-auto-cancel-v1-9000000001',129000,'KRW','PENDING',1,now(),now(),'synthetic-token',now()-interval '1 minute',0)");
        oldCursors = jdbc.queryForList("select * from payment.order_reconciliation_cursor order by lane");
        oldLedger = jdbc.queryForList("select * from payment.payment_cancel");
        oldIntents = jdbc.queryForList("select * from payment.payment_auto_cancel");
    }
    @Test
    void additiveMigrationValidatesAndOldGuardStillSeesExactlyTwoUnchangedSeeds() throws Exception {
        assertThatThrownBy(this::validate).hasMessageContaining("auto_cancel_cursor");
        migrate(resource("payment-auto-cancel-scheduler.sql"));
        validate();
        new PaymentAutoCancelSchemaGuard(source).afterSingletonsInstantiated();
        new PaymentOrderReconciliationSchemaGuard(source).afterSingletonsInstantiated();
        assertThat(jdbc.queryForList("select * from payment.order_reconciliation_cursor order by lane")).isEqualTo(oldCursors);
        assertThat(jdbc.queryForList("select * from payment.payment_cancel")).isEqualTo(oldLedger);
        assertThat(jdbc.queryForList("select * from payment.payment_auto_cancel")).isEqualTo(oldIntents);
        assertThat(jdbc.queryForList("select lane from payment.auto_cancel_cursor order by lane", String.class))
            .containsExactly("LATE_APPROVED", "PENDING", "REJECTED");
        assertThat(jdbc.queryForList("select last_id,cycle_upper_id,version from payment.auto_cancel_cursor"))
            .allSatisfy(row -> {
                assertThat(row.get("last_id")).isEqualTo(0L);
                assertThat(row.get("cycle_upper_id")).isEqualTo(0L);
                assertThat(row.get("version")).isEqualTo(0L);
            });
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname='payment' and indexname='idx_payment_status_id'", Long.class))
            .isEqualTo(1);
        for (String invalid : List.of("last_id=-1", "last_id=1", "lane='INVALID'", "due_before=null", "version=null")) {
            assertThatThrownBy(() -> jdbc.update("update payment.auto_cancel_cursor set " + invalid + " where lane='PENDING'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
    @Test
    void actualPre579DatabaseAcceptsBothOperationalScriptsWithoutChangingExistingPositiveReaders() throws Exception {
        jdbc.execute("drop schema payment cascade");
        jdbc.execute(resource("payment-schema-pre-591.sql"));
        migrate(resource("payment-order-reconciliation.sql"));
        jdbc.update("update payment.order_reconciliation_cursor set cycle_upper_id=100,last_id=10,version=3");
        jdbc.update("insert into payment.payment_cancel(claim_id,created_at,updated_at,payment_id,amount,currency,status,version)"
            + " values(42,now(),now(),9000000001,500,'KRW','SUCCEEDED',0)");
        var beforeCursor = jdbc.queryForList("select * from payment.order_reconciliation_cursor order by lane");
        var beforeLedger = jdbc.queryForList("select * from payment.payment_cancel");
        migrate(resource("payment-auto-cancel.sql"));
        migrate(resource("payment-auto-cancel-scheduler.sql"));
        validate();
        new PaymentAutoCancelSchemaGuard(source).afterSingletonsInstantiated();
        new PaymentOrderReconciliationSchemaGuard(source).afterSingletonsInstantiated();
        assertThat(jdbc.queryForList("select * from payment.order_reconciliation_cursor order by lane")).isEqualTo(beforeCursor);
        assertThat(jdbc.queryForList("select * from payment.payment_cancel")).isEqualTo(beforeLedger);
        assertThat(jdbc.queryForObject("select count(*) from payment.payment_auto_cancel", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment.auto_cancel_cursor", Long.class)).isEqualTo(3);
    }

    @Test
    void failureRollsBackWholeDdlAndSuccessfulScriptCannotResetCursorOnReplay() throws Exception {
        String script = resource("payment-auto-cancel-scheduler.sql");
        assertThatThrownBy(() -> migrate(script.replace("commit;", "select 1/0; commit;"))).isInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("select to_regclass('payment.auto_cancel_cursor')", String.class)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname='payment' and indexname='idx_payment_status_id'", Long.class)).isZero();
        assertThat(jdbc.queryForList("select * from payment.order_reconciliation_cursor order by lane")).isEqualTo(oldCursors);
        migrate(script);
        jdbc.update("update payment.auto_cancel_cursor set cycle_upper_id=100,last_id=50,version=2 where lane='PENDING'");
        assertThatThrownBy(() -> migrate(script)).isInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("select last_id from payment.auto_cancel_cursor where lane='PENDING'", Long.class)).isEqualTo(50);
        assertThat(jdbc.queryForList("select * from payment.payment_cancel")).isEqualTo(oldLedger);
    }
    void validate() {
        var registry = new StandardServiceRegistryBuilder().applySettings(Map.of(
            "hibernate.connection.datasource", source, "hibernate.default_schema", "payment",
            "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.implicit_naming_strategy", "org.springframework.boot.hibernate.SpringImplicitNamingStrategy",
            "hibernate.hbm2ddl.auto", "validate")).build();
        try {
            var sources = new MetadataSources(registry).addAnnotatedClass(PaymentEntity.class).addAnnotatedClass(PaymentCancelEntity.class)
                .addAnnotatedClass(PaymentAutoCancelEntity.class).addAnnotatedClass(AutoCancelCursorEntity.class);
            try (var factory = sources.buildMetadata().buildSessionFactory()) { assertThat(factory.isOpen()).isTrue(); }
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }
    void migrate(String script) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try { statement.execute(script); }
            catch (SQLException failure) { statement.execute("rollback"); throw failure; }
        }
    }
    static String resource(String name) throws Exception {
        try (var input = PaymentAutoCancelSchedulerUpgradePostgresTest.class.getResourceAsStream("/ddl/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
