package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Frozen baseline 7c5c0a6f DDL, operational ALTER, then current entity validation and direct SQL CHECK tests. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfigurer.class)
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"})
class OrderPaymentSchemaUpgradePostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = upgradedDatabase();
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void isolateRestorationFixtures() {
        jdbc.update("delete from orders.order_stock_restoration where id not in (2,9)");
        jdbc.update("delete from orders.order_payment where id not in (1,2,9,10)");
        jdbc.update("update orders.order_stock_restoration set status='RESTORE_PENDING', hold_code=null, restored_at=null, attempts=0, next_attempt_at=now()");
    }

    private static PostgreSQLContainer upgradedDatabase() {
        PostgreSQLContainer database = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema-c2a-7c5c0a6f.sql");
        try {
            database.start();
            var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            var jdbc = new JdbcTemplate(source);
            // Preserve both valid legacy C1 shapes without backfill.
            insert(jdbc, 1, "REJECTED_ORDER_CANCELED", 51L, null, null);
            insert(jdbc, 2, "REJECTED_ORDER_CANCELED", 52L, 62L, null);
            jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, version, created_at, updated_at) "
                + "values (11, 100, ?, 'RETURN', 'REQUESTED', 0, now(), now())", UUID.randomUUID());
            // 운영 적용 순서 그대로: c2b → #16 PR-2a 1단계 → 2단계 → #17 A1 → #17 B1 → #17 B2c. 현재 매핑 전체를 validate 하므로 뒤 단계도 적용한다.
            var migration = new ResourceDatabasePopulator(new ClassPathResource("ddl/order-payment-c2b-restoration.sql"),
                    new ClassPathResource("ddl/claim-cancellation.sql"), new ClassPathResource("ddl/claim-cancellation-cleanup.sql"),
                    new ClassPathResource("ddl/claim-admin-decision.sql"), new ClassPathResource("ddl/claim-return-approval.sql"),
                    new ClassPathResource("ddl/claim-return-fault.sql"));
            migration.setSqlScriptEncoding("UTF-8");
            migration.execute(source);
            insert(jdbc, 9, "REJECTED_PAYMENT_NOT_DONE", 59L, 69L, "2026-11-02T00:00:00Z");
            insert(jdbc, 10, "REJECTED_ORDER_CANCELED", 60L, 70L, null);
            jdbc.update("update orders.order_payment set restored_at = now() where id = 10");
            RestorationSchemaMigration.run(source);
            RestorationSchemaMigration.run(source);
            var abandonedConfirmation = new ResourceDatabasePopulator(new ClassPathResource("ddl/order-abandoned-confirmation.sql"));
            abandonedConfirmation.setSqlScriptEncoding("UTF-8");
            abandonedConfirmation.execute(source);
            abandonedConfirmation.execute(source);
            return database;
        } catch (RuntimeException | Error failure) {
            database.stop();
            throw failure;
        }
    }

    @Test
    void abandonedConfirmationMigrationPreservesPaymentRecordAndAllowsBothNewOrigins() {
        assertThat(jdbc.queryForObject("select origin from orders.order_stock_restoration where id=2", String.class)).isEqualTo("PAYMENT_RECORD");
        try {
            for (String origin : java.util.List.of("CONFIRMATION_ATTEMPT", "ABANDONED_CONFIRMATION")) {
                assertThat(jdbc.update("update orders.order_stock_restoration set origin=? where id=2", origin)).isEqualTo(1);
            }
            assertThatThrownBy(() -> jdbc.update("update orders.order_stock_restoration set origin='INVALID' where id=2"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.update("update orders.order_stock_restoration set origin='PAYMENT_RECORD' where id=2");
        }
    }

    @Test
    void oldSnapshotAlterAndNewMappingValidatePreserveC1AndEnforceNotDoneChecks() {
        assertThat(jdbc.queryForObject("select count(*) from orders.order_payment where id in (1,2)", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select approved_at from orders.order_payment where id=2", Object.class)).isNull();
        insert(jdbc, 3, "REJECTED_PAYMENT_NOT_DONE", 53L, 63L, "2026-11-02T00:00:00Z");
        insert(jdbc, 4, "REJECTED_PAYMENT_NOT_DONE", null, null, null);
        assertThatThrownBy(() -> insert(jdbc, 5, "REJECTED_PAYMENT_NOT_DONE", 55L, null, "2026-11-02T00:00:00Z"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, 6, "REJECTED_PAYMENT_NOT_DONE", 56L, 66L, null))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, 7, "REJECTED_PAYMENT_NOT_DONE", null, 67L, "2026-11-02T00:00:00Z"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, 8, "REJECTED_ALREADY_PAID", 58L, 68L, "2026-11-02T00:00:00Z"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void decisionMigrationPreservesOldWritesAndEnforcesNewEvidenceCheck() {
        jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, attempt_count, version, created_at, updated_at) "
            + "values (12, 101, ?, 'RETURN', 'REQUESTED', 0, 0, now(), now())", UUID.randomUUID());
        jdbc.update("update orders.claim set version = version + 1 where id = 11");
        assertThat(jdbc.queryForObject("select count(*) from orders.claim where id in (11, 12) and decision_reason is null "
            + "and decided_by_member_id is null and decided_at is null and decision_failure_code is null", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("update orders.claim set decided_by_member_id = 7 where id = 11"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, expected_refund_amount, expected_refund_currency, payment_id, "
            + "attempt_count, next_attempt_at, failure_code, version, created_at, updated_at) "
            + "values (13, 102, ?, 'CANCELLATION', 'RESTORATION_FAILED', 1000, 'KRW', 77, 0, null, 'PRODUCT_028', 0, now(), now())", UUID.randomUUID());
        jdbc.update("update orders.claim set decision_reason = '운영 확인', decided_by_member_id = 7, decided_at = now(), "
            + "decision_failure_code = 'PAYMENT_016' where id = 13");
        assertThat(jdbc.queryForObject("select failure_code from orders.claim where id = 13", String.class)).isEqualTo("PRODUCT_028");
        assertThatThrownBy(() -> jdbc.update("update orders.claim set failure_code = null where id = 13"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void restorationBackfillIsEligibleIdempotentAndPreservesExistingWork() {
        var rows = jdbc.queryForList("select id, claim_id, stock_reservation_id, origin, status, attempts from orders.order_stock_restoration "
            + "where id in (2,9) order by id");
        assertThat(rows).extracting(row -> row.get("id")).containsExactly(2L, 9L);
        assertThat(rows).extracting(row -> row.get("claim_id")).containsExactly(62L, 69L);
        assertThat(rows).extracting(row -> row.get("stock_reservation_id")).containsExactly(52L, 59L);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_stock_restoration where id in (1,10)", Long.class)).isZero();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("origin")).isEqualTo("PAYMENT_RECORD");
            assertThat(row.get("status")).isEqualTo("RESTORE_PENDING");
            assertThat(row.get("attempts")).isEqualTo(0);
        });
        try {
            jdbc.update("update orders.order_stock_restoration set status='HELD', hold_code='PRODUCT_028', next_attempt_at=null, attempts=3 where id=2");
            jdbc.update("update orders.order_stock_restoration set status='RESTORED', restored_at=now(), next_attempt_at=null where id=9");
            var before = jdbc.queryForList("select * from orders.order_stock_restoration where id in (2,9) order by id");
            RestorationSchemaMigration.run(jdbc.getDataSource());
            assertThat(jdbc.queryForList("select * from orders.order_stock_restoration where id in (2,9) order by id")).isEqualTo(before);
        } finally {
            jdbc.update("update orders.order_stock_restoration set status='RESTORE_PENDING', hold_code=null, restored_at=null, attempts=0, next_attempt_at=now()");
        }
    }

    @Test
    void restorationBackfillRejectsDuplicateReservationOrClaimAndRollsBackWholeBatch() {
        for (boolean duplicateClaim : new boolean[] {false, true}) {
            try {
                insert(jdbc, 201, "REJECTED_ORDER_CANCELED", 251L, 261L, null);
                insert(jdbc, 202, "REJECTED_ORDER_CANCELED", duplicateClaim ? 252L : 251L, duplicateClaim ? 261L : 262L, null);
                assertThatThrownBy(() -> RestorationSchemaMigration.run(jdbc.getDataSource())).isInstanceOf(IllegalStateException.class);
                assertThat(jdbc.queryForObject("select count(*) from orders.order_stock_restoration where id in (201,202)", Long.class)).isZero();
            } finally {
                jdbc.update("delete from orders.order_payment where id in (201,202)");
            }
        }
    }

    @Test
    void failedFirstMigrationRollsBackCreatedTableAndPreservesSourceRows() {
        String databaseName = "restoration_rollback_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create database " + databaseName);
        try {
            var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + databaseName),
                POSTGRES.getUsername(), POSTGRES.getPassword());
            var isolated = new JdbcTemplate(source);
            isolated.execute("create schema orders");
            isolated.execute("create table orders.order_payment (id bigint, order_id bigint, payment_id bigint, restoration_claim_id bigint, "
                + "stock_reservation_id bigint, result varchar(50), restored_at timestamptz)");
            isolated.update("insert into orders.order_payment values (1,10,20,101,201,'REJECTED_ORDER_CANCELED',null), "
                + "(2,10,21,102,201,'REJECTED_ORDER_CANCELED',null), (3,10,22,103,203,'REJECTED_ORDER_CANCELED',null)");
            var before = isolated.queryForList("select * from orders.order_payment order by id");
            assertThatThrownBy(() -> RestorationSchemaMigration.run(source)).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getSQLState()).isEqualTo("23505"));
            // 최초 CREATE TABLE·COMMENT·INDEX와 backfill이 같은 트랜잭션에서 롤백되는지 확인한다.
            assertThat(isolated.queryForObject("select to_regclass('orders.order_stock_restoration')", String.class)).isNull();
            assertThat(isolated.queryForList("select * from orders.order_payment order by id")).isEqualTo(before);
        } finally {
            jdbc.execute("drop database " + databaseName);
        }
    }

    @Test
    void restorationBackfillRejectsSameClaimWithDifferentOwnershipAndKeepsExistingRow() {
        for (String column : java.util.List.of("order_id", "payment_id", "stock_reservation_id")) {
            try {
                jdbc.update("update orders.order_stock_restoration set " + column + "=" + column + "+1000 where id=2");
                var before = jdbc.queryForList("select * from orders.order_stock_restoration order by id");
                assertThatThrownBy(() -> RestorationSchemaMigration.run(jdbc.getDataSource())).isInstanceOf(IllegalStateException.class);
                assertThat(jdbc.queryForList("select * from orders.order_stock_restoration order by id")).isEqualTo(before);
            } finally {
                jdbc.update("update orders.order_stock_restoration set " + column + "=" + column + "-1000 where id=2");
            }
        }
    }

    private static void insert(JdbcTemplate jdbc, long id, String result, Long reservation, Long claim, String approvedAt) {
        jdbc.update("insert into orders.order_payment (id,order_id,payment_id,member_id,total_amount,total_currency,result,stock_reservation_id,"
            + "restoration_claim_id,approved_at,event_id,created_at,updated_at) values (?,100,?,7,1000,'KRW',?,?,?,cast(? as timestamptz),?,now(),now())",
            id, id, result, reservation, claim, approvedAt, UUID.randomUUID());
    }
}
