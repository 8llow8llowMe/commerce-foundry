package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentWebhookInboxEntity;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Frozen 66b5af4f schema -> valid existing rows -> exact operational script -> current mapping validate. */
@Testcontainers
@EnabledWithDockerOrOnCi
class PaymentProcessedEventUpgradePostgresTest {
    static final String OLD = "payment-schema-pre-c3a-66b5af4f.sql";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/" + OLD);
    DataSource source;
    JdbcTemplate jdbc;
    List<Map<String, Object>> beforeRows;
    List<Map<String, Object>> beforeConstraints;
    List<Map<String, Object>> beforeIndexes;
    List<Map<String, Object>> beforeColumns;

    @BeforeEach
    void oldDatabaseWithValidRows() throws Exception {
        byte[] bytes = resource(OLD);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotStartWith("\uFEFF");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("bc8df389b815be9c913191c879fca6a7a0dfa229e9c167ae0c28d18e60fe9be5");
        assertThat(new String(bytes, StandardCharsets.UTF_8).lines().count()).isEqualTo(285);
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("drop schema payment cascade");
        jdbc.execute(new String(bytes, StandardCharsets.UTF_8));
        int id = 100;
        for (String state : List.of("READY", "IN_PROGRESS", "UNKNOWN", "DONE", "PARTIAL_CANCELED", "CANCELED", "FAILED", "ABORTED",
            "LATE_APPROVED", "LATE_APPROVED_ABORTED", "CANCELED_FAILED", "CANCELED_ABORTED", "WAITING_FOR_DEPOSIT")) {
            seed(++id, state);
        }
        beforeRows = rows();
        beforeConstraints = constraints();
        beforeIndexes = indexes();
        beforeColumns = columns("payment");
    }

    @Test
    void upgradePreservesExistingRowsAndCatalogAndEnforcesInboxAndExistingPaymentConstraints() throws Exception {
        // cleanup OFF is not a schema exemption: none can start; validate must detect the missing entity table.
        mapping("none");
        assertThatThrownBy(() -> mapping("validate")).hasMessageContaining("processed_event");
        migrate();
        mapping("validate");
        assertThat(rows()).isEqualTo(beforeRows);
        assertThat(constraints()).isEqualTo(beforeConstraints);
        assertThat(indexes()).isEqualTo(beforeIndexes);
        assertThat(columns("payment")).isEqualTo(beforeColumns);
        assertThat(jdbc.queryForList("select column_name,data_type,is_nullable,character_maximum_length,datetime_precision"
            + " from information_schema.columns where table_schema='payment' and table_name='processed_event' order by ordinal_position"))
            .extracting(row -> row.get("column_name") + ":" + row.get("data_type") + ":" + row.get("is_nullable")
                + ":" + row.get("character_maximum_length") + ":" + row.get("datetime_precision"))
            .containsExactly("id:bigint:NO:null:null", "created_at:timestamp with time zone:NO:null:6",
                "updated_at:timestamp with time zone:NO:null:6", "consumer_group:character varying:NO:200:null", "event_id:uuid:NO:null:null",
                "event_type:character varying:NO:100:null", "processed_at:timestamp with time zone:NO:null:6");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname='payment' and tablename='processed_event'", String.class))
            .containsExactlyInAnyOrder("processed_event_pkey", "uk_processed_event_consumer_group_event_id", "idx_processed_event_processed_at");
        assertThat(jdbc.queryForObject("select pg_get_indexdef(indexrelid) from pg_index where indexrelid="
            + "'payment.idx_processed_event_processed_at'::regclass", String.class)).endsWith("USING btree (processed_at)");
        assertThat(jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='payment.processed_event'::regclass"
            + " and conname='uk_processed_event_consumer_group_event_id'", String.class)).isEqualTo("UNIQUE (consumer_group, event_id)");
        assertThat(jdbc.queryForObject("select obj_description('payment.processed_event'::regclass)", String.class))
            .isEqualTo("처리한 이벤트 기록 (멱등 컨슈머 inbox)");
        assertThat(jdbc.queryForList("select col_description(attrelid,attnum) from pg_attribute"
            + " where attrelid='payment.processed_event'::regclass and attnum>0 order by attnum", String.class))
            .containsExactly("처리 기록 아이디 (Snowflake)", "생성 시각", "수정 시각",
                "컨슈머 그룹 (예: commerce-foundry.prod.payment.order-payment-result)", "이벤트 아이디 (봉투 eventId)",
                "이벤트 종류 (예: order-paid)", "처리 시각 — 보존 기간 정리 기준");
        assertThat(comment("order_synced")).contains("order 회신(order-paid)");
        assertThat(comment("order_sync_failure_code")).contains("order 거절 회신(order-payment-rejected)");
        UUID event = UUID.randomUUID();
        insertInbox(1, "group", event, "order-paid");
        insertInbox(2, "other", event, "order-paid");
        assertThatThrownBy(() -> insertInbox(3, "group", event, "order-paid")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInbox(1, "other", UUID.randomUUID(), "order-paid"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for (String column : List.of("id", "created_at", "updated_at", "consumer_group", "event_id", "event_type", "processed_at")) {
            assertThatThrownBy(() -> jdbc.update("update payment.processed_event set " + column + "=null where id=1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insertInbox(4, "g".repeat(201), UUID.randomUUID(), "order-paid"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInbox(4, "group", UUID.randomUUID(), "t".repeat(101)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        insertInbox(-1, "g".repeat(200), UUID.randomUUID(), "t".repeat(100)); // No invented positive-ID CHECK.
        for (String state : List.of("DONE", "PARTIAL_CANCELED", "CANCELED")) {
            jdbc.update("update payment.payment set order_synced=true where status=? and failure_code is null and abort_reason is null", state);
            jdbc.update("update payment.payment set order_synced=false,order_sync_failure_code='ORDER_CANCELED'"
                + " where status=? and failure_code is null and abort_reason is null", state);
            assertThatThrownBy(() -> jdbc.update("update payment.payment set order_synced=true where status=? and order_sync_failure_code is not null", state))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        for (String state : List.of("READY", "IN_PROGRESS", "UNKNOWN", "FAILED", "ABORTED", "LATE_APPROVED", "WAITING_FOR_DEPOSIT")) {
            assertThatThrownBy(() -> jdbc.update("update payment.payment set order_synced=true where status=?", state))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update payment.payment set order_sync_failure_code='ORDER_CANCELED' where status=?", state))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test
    void midScriptFailureRollsBackTableAndCommentsAndRequiresCatalogCheckBeforeRetry() throws Exception {
        String oldComment = comment("order_synced");
        jdbc.execute("create index idx_processed_event_processed_at on payment.payment(id)");
        assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("select to_regclass('payment.processed_event')", String.class)).isNull();
        assertThat(comment("order_synced")).isEqualTo(oldComment);
        assertThat(rows()).isEqualTo(beforeRows);
        assertThat(constraints()).isEqualTo(beforeConstraints);
        jdbc.execute("drop index payment.idx_processed_event_processed_at");
        assertThat(indexes()).isEqualTo(beforeIndexes);
        migrate();
        mapping("validate");
        // Applied scripts are not idempotent. A failed replay must preserve the committed table and rows.
        insertInbox(1, "group", UUID.randomUUID(), "order-paid");
        assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment.processed_event", Long.class)).isEqualTo(1);
    }

    void mapping(String action) {
        var registry = new StandardServiceRegistryBuilder().applySettings(Map.of(
            "hibernate.connection.datasource", source, "hibernate.default_schema", "payment", "hibernate.hbm2ddl.auto", action,
            "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.implicit_naming_strategy", "org.springframework.boot.hibernate.SpringImplicitNamingStrategy")).build();
        try {
            var sources = new MetadataSources(registry);
            List.of(PaymentEntity.class, PaymentCancelEntity.class, PaymentWebhookInboxEntity.class, OutboxEntity.class,
                ShedLockEntity.class, ProcessedEventEntity.class).forEach(sources::addAnnotatedClass);
            try (var factory = sources.buildMetadata().buildSessionFactory()) {
                assertThat(factory.isOpen()).isTrue();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    void migrate() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute(new String(resource("payment-processed-event.sql"), StandardCharsets.UTF_8));
            } catch (SQLException failure) {
                statement.execute("rollback");
                throw failure;
            }
        }
    }

    static byte[] resource(String name) throws Exception {
        try (var input = PaymentProcessedEventUpgradePostgresTest.class.getResourceAsStream("/ddl/" + name)) {
            return input.readAllBytes();
        }
    }

    void seed(int id, String origin) {
        String status = origin.replace("_FAILED", "").replace("_ABORTED", "");
        boolean failed = origin.equals("FAILED") || origin.equals("LATE_APPROVED") || origin.endsWith("_FAILED");
        boolean aborted = origin.equals("ABORTED") || origin.endsWith("_ABORTED");
        boolean approved = List.of("DONE", "PARTIAL_CANCELED", "CANCELED", "LATE_APPROVED").contains(status);
        jdbc.update("insert into payment.payment(id,created_at,updated_at,version,order_id,active_order_id,member_id,pg_order_id,gateway,"
            + "total_amount,total_currency,order_name,status,payment_key,approval_started_at,approved_at,method,failure_code,failure_message,"
            + "abort_reason,unknown_reason,order_synced) values (?,now(),now(),7,?,?,7,?,'TOSS',129000,?,'synthetic',?,?,"
            + "cast(? as timestamptz),cast(? as timestamptz),?,?,?,?,?,false)",
            id, id + 1000, failed || aborted ? null : id + 1000, "legacy-" + id, id % 2 == 0 ? "KRW" : "USD", status, "synthetic-key-" + id,
            "2026-11-01T00:00:00.123456Z", approved ? "2026-11-01T00:00:01.123456Z" : null, approved ? "CARD" : null,
            failed ? "SYNTHETIC_DECLINED" : null, failed ? "synthetic decline" : null, aborted ? "ADMIN" : null,
            status.equals("UNKNOWN") ? "TIMEOUT" : null);
    }

    void insertInbox(long id, String group, UUID event, String type) {
        jdbc.update("insert into payment.processed_event values (?,now(),now(),?,?,?,now())", id, group, event, type);
    }

    List<Map<String, Object>> rows() { return jdbc.queryForList("select * from payment.payment order by id"); }
    List<Map<String, Object>> constraints() {
        return jdbc.queryForList("select conname,pg_get_constraintdef(oid) from pg_constraint where conrelid='payment.payment'::regclass order by conname");
    }
    List<Map<String, Object>> indexes() {
        return jdbc.queryForList("select indexname,indexdef from pg_indexes where schemaname='payment' and tablename='payment' order by indexname");
    }
    List<Map<String, Object>> columns(String table) {
        return jdbc.queryForList("select column_name,data_type,is_nullable,character_maximum_length,datetime_precision"
            + " from information_schema.columns where table_schema='payment' and table_name=? order by ordinal_position", table);
    }
    String comment(String column) {
        return jdbc.queryForObject("select col_description(attrelid,attnum) from pg_attribute"
            + " where attrelid='payment.payment'::regclass and attname=?", String.class, column);
    }
}
