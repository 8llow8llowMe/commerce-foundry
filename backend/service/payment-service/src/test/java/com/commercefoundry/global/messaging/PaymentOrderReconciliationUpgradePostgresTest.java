package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.OrderReconciliationCursorEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
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

/** 실제 구 DB에 생성 DDL을 적용한 뒤 validate한다. 기존 테이블의 ALTER는 필요 없다. */
@Testcontainers
@EnabledWithDockerOrOnCi
class PaymentOrderReconciliationUpgradePostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    DataSource source;
    JdbcTemplate jdbc;
    List<Map<String, Object>> before;
    List<Map<String, Object>> beforeIndexes;
    List<Map<String, Object>> beforeConstraints;

    @BeforeEach
    void oldDatabase() throws Exception {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("drop schema if exists payment cascade");
        jdbc.execute(resource("payment-schema-pre-591.sql"));
        jdbc.update("insert into payment.payment(id,created_at,updated_at,version,order_id,active_order_id,member_id,pg_order_id,gateway,"
            + "total_amount,total_currency,order_name,status,payment_key,approval_started_at,approved_at,method,order_synced)"
            + " values(9000000001,now(),now(),7,8000000001,8000000001,7000000001,'synthetic-591','TOSS',129000,'KRW','synthetic',"
            + "'DONE','synthetic-key','2026-11-01T00:00:00.123456Z','2026-11-01T00:00:01.123456Z','CARD',false)");
        before = jdbc.queryForList("select * from payment.payment");
        beforeIndexes = jdbc.queryForList("select tablename,indexname,indexdef from pg_indexes where schemaname='payment' order by tablename,indexname");
        beforeConstraints = jdbc.queryForList("select conname,pg_get_constraintdef(oid) from pg_constraint"
            + " where connamespace='payment'::regnamespace order by conname");
    }

    @Test
    void actualOldSchemaToGeneratedAdditiveDdlThenValidatePreservesRowsAndExistingCatalog() throws Exception {
        assertThatThrownBy(this::validate).hasMessageContaining("order_reconciliation_cursor");
        migrate(resource("payment-order-reconciliation.sql"));
        validate();
        assertThat(jdbc.queryForList("select * from payment.payment")).isEqualTo(before);
        assertThat(jdbc.queryForList("select tablename,indexname,indexdef from pg_indexes where schemaname='payment'"
            + " and tablename<>'order_reconciliation_cursor' order by tablename,indexname")).isEqualTo(beforeIndexes);
        assertThat(jdbc.queryForList("select conname,pg_get_constraintdef(oid) from pg_constraint where connamespace='payment'::regnamespace"
            + " and conrelid<>'payment.order_reconciliation_cursor'::regclass order by conname")).isEqualTo(beforeConstraints);
        assertThat(jdbc.queryForList("select lane,last_id,cycle_upper_id,version from payment.order_reconciliation_cursor order by lane"))
            .hasSize(2).allSatisfy(row -> {
                assertThat(row.get("last_id")).isEqualTo(0L);
                assertThat(row.get("cycle_upper_id")).isEqualTo(0L);
                assertThat(row.get("version")).isEqualTo(0L);
            });
        assertThat(jdbc.queryForObject("select obj_description('payment.order_reconciliation_cursor'::regclass)", String.class))
            .isEqualTo("주문 회신 복구 lane별 영속 순회 커서");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname='payment' and tablename='order_reconciliation_cursor'", String.class))
            .containsExactly("order_reconciliation_cursor_pkey"); // lane PK만으로 두 행을 잠근다. 중복 인덱스를 두지 않는다.
        for (String assignment : List.of("last_id=-1", "last_id=1", "lane='INVALID'", "lane=null", "approved_before=null", "version=null")) {
            assertThatThrownBy(() -> jdbc.update("update payment.order_reconciliation_cursor set " + assignment + " where lane='RECONCILE'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        jdbc.update("update payment.order_reconciliation_cursor set cycle_upper_id=10,last_id=5 where lane='RECONCILE'");
        assertThat(jdbc.queryForObject("select last_id from payment.order_reconciliation_cursor where lane='RECONCILE'", Long.class)).isEqualTo(5);
    }

    @Test
    void ddlFailureRollsBackWholeUpgradeAndReapplicationRequiresExplicitInspection() throws Exception {
        var script = resource("payment-order-reconciliation.sql");
        assertThatThrownBy(() -> migrate(script.replace("commit;", "select 1/0; commit;"))).isInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("select to_regclass('payment.order_reconciliation_cursor')", String.class)).isNull();
        assertThat(jdbc.queryForList("select * from payment.payment")).isEqualTo(before);
        migrate(script);
        validate();
        assertThatThrownBy(() -> migrate(script)).isInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment.order_reconciliation_cursor", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select * from payment.payment")).isEqualTo(before);
    }

    void validate() {
        var registry = new StandardServiceRegistryBuilder().applySettings(Map.of(
            "hibernate.connection.datasource", source,
            "hibernate.default_schema", "payment",
            "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.implicit_naming_strategy", "org.springframework.boot.hibernate.SpringImplicitNamingStrategy",
            "hibernate.hbm2ddl.auto", "validate"
        )).build();
        try {
            try (var factory = new MetadataSources(registry).addAnnotatedClass(PaymentEntity.class)
                .addAnnotatedClass(OrderReconciliationCursorEntity.class).buildMetadata().buildSessionFactory()) {
                assertThat(factory.isOpen()).isTrue();
            }
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }

    void migrate(String script) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try { statement.execute(script); }
            catch (SQLException failure) { statement.execute("rollback"); throw failure; }
        }
    }

    static String resource(String name) throws Exception {
        try (var input = PaymentOrderReconciliationUpgradePostgresTest.class.getResourceAsStream("/ddl/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
