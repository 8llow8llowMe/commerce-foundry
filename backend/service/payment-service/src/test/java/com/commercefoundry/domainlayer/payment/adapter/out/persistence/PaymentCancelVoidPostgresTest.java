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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 무환불 봉인 계약(#691)을 PostgreSQL 로 돌리고, payment_cancel.status check 운영 스크립트 두 개를 검증한다 —
 * #691 VOIDED(4값) · #693 외부 확인 환불(6값). 컨테이너를 한 클래스가 공유하므로 스크립트 테스트는 끝에 언제나 6값으로 되돌린다.
 */
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment"})
class PaymentCancelVoidPostgresTest extends PaymentCancelVoidContract {

    private static final String VOIDED_SCRIPT = "ddl/payment-cancel-voided-status.sql";
    private static final String EXTERNAL_REFUND_SCRIPT = "ddl/payment-cancel-external-refund-status.sql";
    private static final String PRE_691_CHECK = "status in ('PENDING','SUCCEEDED','REJECTED')";
    private static final String PRE_693_CHECK = "status in ('PENDING','SUCCEEDED','REJECTED','VOIDED')";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired DataSource dataSource;

    @Test
    void operationalScriptReplacesDefaultNamedStatusCheckAndCanRunTwice() throws Exception {
        save(done());
        // #691 이전 제약을 되살린다 — 스냅숏의 이름 없는 칼럼 check 는 PostgreSQL 기본 이름이다
        replaceStatusCheck(PRE_691_CHECK);
        try {
            // 미적용 상태의 봉인은 check 위반으로 실패한다 — 원장은 그대로다
            assertThatThrownBy(() -> cancellations.insertVoided(PaymentCancel.voided(42, PAYMENT_ID, krw(10_000))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(cancellations.findByClaimId(42)).isEmpty();
            migrate(VOIDED_SCRIPT);
            migrate(VOIDED_SCRIPT);
            assertThat(validatedStatusChecks()).isEqualTo(1);
            cancellations.insertVoided(PaymentCancel.voided(42, PAYMENT_ID, krw(10_000)));
            assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.VOIDED);
            assertThatThrownBy(() -> insertRaw(43, "UNKNOWN_STATE")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            // 여기서는 check 가 3값 또는 4값이다 — 같은 컨테이너의 다른 테스트를 위해 #693 스크립트로 6값을 되살린다.
            // VOIDED 스크립트는 다시 돌리지 않는다: 3·4값 어느 쪽이든 #693 스크립트 하나로 6값이 되고, 6값 위의 VOIDED 스크립트는 자기 점검이 막는다.
            migrate(EXTERNAL_REFUND_SCRIPT);
        }
    }

    @ParameterizedTest(name = "출발 check: {0}")
    @ValueSource(strings = {PRE_691_CHECK, PRE_693_CHECK})
    void externalRefundScriptWidensCheckFromThreeOrFourValuesAndCanRunAgainOverNewRows(String startingCheck) throws Exception {
        save(done());
        replaceStatusCheck(startingCheck);
        try {
            // 미적용 상태에서는 새 상태를 쓸 수 없다 — 애플리케이션보다 DDL 이 먼저인 이유
            assertThatThrownBy(() -> insertRaw(41, "EXTERNAL_PG_REFUNDED")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(cancellations.findByPaymentId(PAYMENT_ID)).isEmpty();
            migrate(EXTERNAL_REFUND_SCRIPT);
            assertThat(validatedStatusChecks()).isEqualTo(1);
            insertRaw(41, "EXTERNAL_PG_REFUNDED");
            insertRaw(42, "OFF_PG_REFUNDED");
            cancellations.insertVoided(PaymentCancel.voided(43, PAYMENT_ID, krw(1)));
            // 새 상태 행이 있어도 다시 실행할 수 있다 — 최종 check 가 기존 값을 모두 포함한다
            migrate(EXTERNAL_REFUND_SCRIPT);
            assertThat(validatedStatusChecks()).isEqualTo(1);
            assertThat(statusCheckDefinition()).contains("'PENDING'", "'SUCCEEDED'", "'REJECTED'", "'VOIDED'", "'EXTERNAL_PG_REFUNDED'", "'OFF_PG_REFUNDED'");
            assertThat(cancellations.findByPaymentId(PAYMENT_ID)).extracting(PaymentCancel::status).containsExactlyInAnyOrder(
                PaymentCancel.Status.EXTERNAL_PG_REFUNDED, PaymentCancel.Status.OFF_PG_REFUNDED, PaymentCancel.Status.VOIDED);
            assertThatThrownBy(() -> insertRaw(44, "UNKNOWN_STATE")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.update("delete from payment.payment_cancel");
            migrate(EXTERNAL_REFUND_SCRIPT);
        }
    }

    @ParameterizedTest(name = "외부 환불 행 있음={0}")
    @ValueSource(booleans = {false, true})
    void voidedScriptRefusesToNarrowExternalRefundCheck(boolean withExternalRefundRow) throws Exception {
        // 적용 순서를 거꾸로 돌리면(#693 뒤 #691 재실행) 자기 점검이 DROP 전에 P0001 로 막는다 — 행이 없어도 6값이 4값으로 조용히 줄지 않는다
        save(done());
        if (withExternalRefundRow) {
            insertRaw(41, "OFF_PG_REFUNDED");
        }
        String before = statusCheckDefinition();
        try {
            assertThatThrownBy(() -> migrate(VOIDED_SCRIPT)).isInstanceOfSatisfying(SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"));
            assertThat(statusCheckDefinition()).isEqualTo(before).contains("'EXTERNAL_PG_REFUNDED'", "'OFF_PG_REFUNDED'");
            assertThat(validatedStatusChecks()).isEqualTo(1);
            insertRaw(42, "EXTERNAL_PG_REFUNDED");
        } finally {
            jdbc.update("delete from payment.payment_cancel");
            migrate(EXTERNAL_REFUND_SCRIPT);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {VOIDED_SCRIPT, EXTERNAL_REFUND_SCRIPT})
    void selfCheckRefusesUnexpectedConstraintNameAndChangesNothing(String script) throws Exception {
        jdbc.execute("alter table payment.payment_cancel rename constraint payment_cancel_status_check to payment_cancel_status_check_legacy");
        String definition = "select pg_get_constraintdef(oid) from pg_constraint where conrelid='payment.payment_cancel'::regclass"
            + " and conname='payment_cancel_status_check_legacy'";
        String before = jdbc.queryForObject(definition, String.class);
        try {
            // 자기 점검(DO 블록)이 DROP 전에 실패하고 트랜잭션이 롤백된다 — 이름이 다른 제약을 지우거나 둘을 만들지 않는다
            assertThatThrownBy(() -> migrate(script)).isInstanceOfSatisfying(SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"));
            assertThat(jdbc.queryForObject(definition, String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='payment.payment_cancel'::regclass"
                + " and contype='c' and pg_get_constraintdef(oid) ilike '%status%'", Long.class)).isEqualTo(1);
        } finally {
            jdbc.execute("alter table payment.payment_cancel rename constraint payment_cancel_status_check_legacy to payment_cancel_status_check");
        }
    }

    private void replaceStatusCheck(String check) {
        jdbc.execute("alter table payment.payment_cancel drop constraint payment_cancel_status_check");
        jdbc.execute("alter table payment.payment_cancel add constraint payment_cancel_status_check check (" + check + ")");
    }

    private void insertRaw(long claimId, String status) {
        jdbc.update("insert into payment.payment_cancel (claim_id, created_at, updated_at, amount, currency, payment_id, status)"
            + " values (?, now(), now(), 1, 'KRW', ?, ?)", claimId, PAYMENT_ID, status);
    }

    private long validatedStatusChecks() {
        return jdbc.queryForObject("select count(*) from pg_constraint where conrelid='payment.payment_cancel'::regclass"
            + " and conname='payment_cancel_status_check' and convalidated", Long.class);
    }

    private String statusCheckDefinition() {
        return jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='payment.payment_cancel'::regclass"
            + " and conname='payment_cancel_status_check'", String.class);
    }

    private void migrate(String script) throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(script)) {
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
