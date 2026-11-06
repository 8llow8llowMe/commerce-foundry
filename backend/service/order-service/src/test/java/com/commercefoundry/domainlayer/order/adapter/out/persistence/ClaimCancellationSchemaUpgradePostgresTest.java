package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 직전 운영 형태(동결 7c5c0a6f 스냅숏 + C2b ALTER = develop 84eee730 의 스냅숏)에 반품 행을 넣고 1단계 {@code ddl/claim-cancellation.sql} →
 * 이전 앱 모양의 INSERT(attempt_count 생략) → 2단계 {@code ddl/claim-cancellation-cleanup.sql} 을 적용한 뒤 (#16 PR-2a-1) 엔티티 검증({@code validate})이 통과하고,
 * claim · claim_item 의 열 · 제약 · 인덱스 · 코멘트가 새 스냅숏({@code ddl/order-schema.sql})으로 만든 DB 와 같은지 본다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfigurer.class)
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"})
class ClaimCancellationSchemaUpgradePostgresTest {
    private static final long LEGACY_CLAIM_ID = 11L;
    private static final long ROLLING_CLAIM_ID = 14L;

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = upgradedDatabase();
    @Autowired JdbcTemplate jdbc;

    private static PostgreSQLContainer upgradedDatabase() {
        PostgreSQLContainer database = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema-c2a-7c5c0a6f.sql");
        try {
            database.start();
            var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            run(source, "ddl/order-payment-c2b-restoration.sql");
            var jdbc = new JdbcTemplate(source);
            jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, version, created_at, updated_at) "
                + "values (?, 100, ?, 'RETURN', 'REQUESTED', 0, now(), now())", LEGACY_CLAIM_ID, UUID.randomUUID());
            jdbc.update("insert into orders.claim_item (id, claim_id, order_item_id, quantity, reason, created_at, updated_at) "
                + "values (12, ?, 13, 1, 'DEFECTIVE', now(), now())", LEGACY_CLAIM_ID);
            run(source, "ddl/claim-cancellation.sql");
            // 1단계와 2단계 사이 — 롤링 배포 중 이전 앱은 새 열을 모르는 INSERT 를 한다(attempt_count 생략). DEFAULT 0 이 받아야 한다
            jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, version, created_at, updated_at) "
                + "values (?, 101, ?, 'RETURN', 'REQUESTED', 0, now(), now())", ROLLING_CLAIM_ID, UUID.randomUUID());
            run(source, "ddl/claim-cancellation-cleanup.sql");
            jdbc.execute("create database fresh");
            run(new DriverManagerDataSource(database.getJdbcUrl().replace("/" + database.getDatabaseName(), "/fresh"), database.getUsername(),
                database.getPassword()), "ddl/order-schema.sql");
            return database;
        } catch (RuntimeException | Error failure) {
            database.stop();
            throw failure;
        }
    }

    private static void run(DataSource source, String script) {
        var populator = new ResourceDatabasePopulator(new ClassPathResource(script));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(source);
    }

    private static JdbcTemplate fresh() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/fresh"),
            POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static List<Map<String, Object>> shape(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
            select 'column' as kind, table_name || '.' || column_name as name,
                   data_type || coalesce('(' || character_maximum_length || ')', '') || ' ' || is_nullable || ' ' || coalesce(column_default, '') as definition
              from information_schema.columns where table_schema = 'orders' and table_name in ('claim', 'claim_item')
            union all
            select 'constraint', rel.relname || '.' || con.conname, pg_get_constraintdef(con.oid)
              from pg_constraint con join pg_class rel on rel.oid = con.conrelid join pg_namespace ns on ns.oid = rel.relnamespace
             where ns.nspname = 'orders' and rel.relname in ('claim', 'claim_item')
            union all
            select 'index', tablename || '.' || indexname, indexdef from pg_indexes where schemaname = 'orders' and tablename in ('claim', 'claim_item')
            union all
            select 'comment', c.table_name || '.' || c.column_name, col_description(('orders.' || c.table_name)::regclass, c.ordinal_position)
              from information_schema.columns c where c.table_schema = 'orders' and c.table_name in ('claim', 'claim_item')
            union all
            select 'comment', relname, obj_description(oid, 'pg_class') from pg_class
             where relnamespace = 'orders'::regnamespace and relname in ('claim', 'claim_item')
            order by 1, 2
            """);
    }

    @Test
    void upgradedClaimTablesMatchFreshSnapshotAndPreserveLegacyReturn() {
        assertThat(shape(jdbc)).isEqualTo(shape(fresh()));
        assertThat(jdbc.queryForMap("select type, status, attempt_count, payment_id, next_attempt_at, failure_code from orders.claim where id = ?",
            LEGACY_CLAIM_ID)).containsEntry("type", "RETURN").containsEntry("status", "REQUESTED").containsEntry("attempt_count", 0)
            .containsEntry("payment_id", null).containsEntry("next_attempt_at", null).containsEntry("failure_code", null);
        assertThat(jdbc.queryForObject("select attempt_count from orders.claim where id = ?", Integer.class, ROLLING_CLAIM_ID))
            .as("1단계 뒤 이전 앱 INSERT 는 DEFAULT 0 으로 들어갔다").isZero();
        assertThatThrownBy(() -> jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, version, created_at, updated_at) "
            + "values (15, 102, ?, 'RETURN', 'REQUESTED', 0, now(), now())", UUID.randomUUID()))
            .as("2단계 뒤에는 기본값이 없다(스냅숏과 같다)").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upgradedChecksAcceptCancellationAndRejectMissingPayment() {
        jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, expected_refund_amount, expected_refund_currency, payment_id, "
            + "attempt_count, next_attempt_at, failure_code, version, created_at, updated_at) values (21, 100, ?, 'CANCELLATION', 'RESTORATION_FAILED', 1000, "
            + "'KRW', 77, 0, null, 'PRODUCT_028', 0, now(), now())", UUID.randomUUID());
        jdbc.update("insert into orders.claim_item (id, claim_id, order_item_id, quantity, reason, created_at, updated_at) "
            + "values (22, 21, 13, 1, 'PAYMENT_ISSUE', now(), now())");
        assertThatThrownBy(() -> jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, attempt_count, version, created_at, updated_at) "
            + "values (23, 100, ?, 'CANCELLATION', 'REQUESTED', 0, 0, now(), now())", UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
