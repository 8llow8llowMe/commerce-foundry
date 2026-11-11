package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment"})
class PaymentCancelVoidPostgresTest extends PaymentCancelVoidContract {

    private static final String OLD_CHECK = "status in ('PENDING','SUCCEEDED','REJECTED')";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired DataSource dataSource;

    @Test
    void operationalScriptReplacesDefaultNamedStatusCheckAndCanRunTwice() throws Exception {
        save(done());
        // #691 이전 제약을 되살린다 — 스냅숏의 이름 없는 칼럼 check 는 PostgreSQL 기본 이름이다
        jdbc.execute("alter table payment.payment_cancel drop constraint payment_cancel_status_check");
        jdbc.execute("alter table payment.payment_cancel add constraint payment_cancel_status_check check (" + OLD_CHECK + ")");
        try {
            // 미적용 상태의 봉인은 check 위반으로 실패한다 — 원장은 그대로다
            assertThatThrownBy(() -> cancellations.insertVoided(PaymentCancel.voided(42, PAYMENT_ID, krw(10_000))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(cancellations.findByClaimId(42)).isEmpty();
            migrate();
            migrate();
            assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='payment.payment_cancel'::regclass"
                + " and conname='payment_cancel_status_check' and convalidated", Long.class)).isEqualTo(1);
            cancellations.insertVoided(PaymentCancel.voided(42, PAYMENT_ID, krw(10_000)));
            assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.VOIDED);
            assertThatThrownBy(() -> jdbc.update("insert into payment.payment_cancel (claim_id, created_at, updated_at, amount, currency, payment_id, status)"
                + " values (43, now(), now(), 1, 'KRW', ?, 'UNKNOWN_STATE')", PAYMENT_ID))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            migrate();
        }
    }

    @Test
    void selfCheckRefusesUnexpectedConstraintNameAndChangesNothing() throws Exception {
        jdbc.execute("alter table payment.payment_cancel rename constraint payment_cancel_status_check to payment_cancel_status_check_legacy");
        String definition = "select pg_get_constraintdef(oid) from pg_constraint where conrelid='payment.payment_cancel'::regclass"
            + " and conname='payment_cancel_status_check_legacy'";
        String before = jdbc.queryForObject(definition, String.class);
        try {
            // 자기 점검(DO 블록)이 DROP 전에 실패하고 트랜잭션이 롤백된다 — 이름이 다른 제약을 지우거나 둘을 만들지 않는다
            assertThatThrownBy(this::migrate).isInstanceOfSatisfying(SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"));
            assertThat(jdbc.queryForObject(definition, String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='payment.payment_cancel'::regclass"
                + " and contype='c' and pg_get_constraintdef(oid) ilike '%status%'", Long.class)).isEqualTo(1);
        } finally {
            jdbc.execute("alter table payment.payment_cancel rename constraint payment_cancel_status_check_legacy to payment_cancel_status_check");
        }
    }

    private void migrate() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream("ddl/payment-cancel-voided-status.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 운영 스크립트의 BEGIN/COMMIT 이 한 물리 커넥션에서 돌아야 한다.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute(sql);
            } catch (SQLException failure) {
                statement.execute("rollback");
                throw failure;
            }
        }
    }
}
