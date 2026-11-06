package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.util.UUID;
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

    private static PostgreSQLContainer upgradedDatabase() {
        PostgreSQLContainer database = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema-c2a-7c5c0a6f.sql");
        try {
            database.start();
            var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            var jdbc = new JdbcTemplate(source);
            // Preserve both valid legacy C1 shapes without backfill.
            insert(jdbc, 1, "REJECTED_ORDER_CANCELED", 51L, null, null);
            insert(jdbc, 2, "REJECTED_ORDER_CANCELED", 52L, 62L, null);
            // 운영 적용 순서 그대로: c2b → #16 PR-2a 1단계 → 2단계 → #17 PR-A. 현재 매핑 전체를 validate 하므로 뒤 단계도 적용한다.
            var migration = new ResourceDatabasePopulator(new ClassPathResource("ddl/order-payment-c2b-restoration.sql"),
                    new ClassPathResource("ddl/claim-cancellation.sql"), new ClassPathResource("ddl/claim-cancellation-cleanup.sql"),
                    new ClassPathResource("ddl/claim-admin-decision.sql"));
            migration.setSqlScriptEncoding("UTF-8");
            migration.execute(source);
            return database;
        } catch (RuntimeException | Error failure) {
            database.stop();
            throw failure;
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

    private static void insert(JdbcTemplate jdbc, long id, String result, Long reservation, Long claim, String approvedAt) {
        jdbc.update("insert into orders.order_payment (id,order_id,payment_id,member_id,total_amount,total_currency,result,stock_reservation_id,"
            + "restoration_claim_id,approved_at,event_id,created_at,updated_at) values (?,100,?,7,1000,'KRW',?,?,?,cast(? as timestamptz),?,now(),now())",
            id, id, result, reservation, claim, approvedAt, UUID.randomUUID());
    }
}
