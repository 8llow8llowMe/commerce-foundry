package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment"})
class PaymentCanceledConstraintPostgresTest extends PaymentCanceledConstraintContract {
    private static final String OLD_CHECK = "(failure_message is null or failure_code is not null)"
        + " and ((status = 'FAILED' and failure_code is not null) or status = 'LATE_APPROVED'"
        + " or (status not in ('FAILED','LATE_APPROVED','CANCELED') and failure_code is null))";
    private static final String DEFINITION = "select pg_get_constraintdef(oid) from pg_constraint"
        + " where conrelid='payment.payment'::regclass and conname='ck_payment_failed_fields'";
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void migrationCanBeAppliedTwiceAndCompleteCanResumePendingWithoutAnotherPgCall() throws Exception {
        save(done());
        processor.prepare(PAYMENT_ID, 42, krw(129_000));
        jdbc.execute("alter table payment.payment drop constraint ck_payment_failed_fields");
        jdbc.execute("alter table payment.payment add constraint ck_payment_failed_fields check (" + OLD_CHECK + ")");
        try {
            assertThatThrownBy(() -> processor.complete(PAYMENT_ID, 42)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(reread().status()).isEqualTo(PaymentStatus.DONE);
            assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
            migrate();
            migrate();
            assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='payment.payment'::regclass"
                + " and conname='ck_payment_failed_fields' and convalidated", Long.class)).isEqualTo(1);
            processor.complete(PAYMENT_ID, 42);
            assertThat(reread().status()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(reread().amount()).isEqualTo(krw(129_000));
            assertThat(reread().approvedAt()).isEqualTo(APPROVED_AT);
            assertSucceeded(42, 129_000);
        } finally {
            migrate();
        }
    }

    @Test
    void addValidationFailureRollsBackDropAndPreservesTheOriginalConstraint() throws Exception {
        save(ready());
        jdbc.execute("alter table payment.payment drop constraint ck_payment_failed_fields");
        jdbc.update("update payment.payment set failure_message='synthetic invalid legacy row' where id=?", PAYMENT_ID);
        jdbc.execute("alter table payment.payment add constraint ck_payment_failed_fields check (" + OLD_CHECK + ") not valid");
        String before = jdbc.queryForObject(DEFINITION, String.class);
        try {
            assertThatThrownBy(this::migrate).isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
            assertThat(jdbc.queryForObject(DEFINITION, String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select convalidated from pg_constraint where conrelid='payment.payment'::regclass"
                + " and conname='ck_payment_failed_fields'", Boolean.class)).isFalse();
        } finally {
            jdbc.update("delete from payment.payment where id=?", PAYMENT_ID);
            migrate();
        }
    }

    private void migrate() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream("ddl/payment-canceled-failed-fields.sql")) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Explicit BEGIN/COMMIT from the production script must execute on this one physical connection.
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
