package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import static org.assertj.core.api.Assertions.*;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
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
class PaymentAutoCancelPostgresTest extends PaymentAutoCancelContract {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void additiveOperationalScriptPreservesExistingPaymentLedgerAndTwoLaneSchema() throws Exception {
        save(done().markOrderSyncFailed("ORDER_CANCELED"));
        orderCancellations.prepare(PAYMENT_ID, 42, krw(29000));
        long lanes = jdbc.queryForObject("select count(*) from payment.order_reconciliation_cursor", Long.class);
        // Recreate only the newly added empty metadata table, retaining all old schema objects and money records.
        jdbc.execute("drop table payment.payment_auto_cancel");
        try {
            try (var input = getClass().getResourceAsStream("/ddl/payment-auto-cancel.sql");
                var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                assertThat(input).isNotNull();
                try {
                    statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception failure) {
                    statement.execute("rollback");
                    throw failure;
                }
            }
            entityManagerFactory.unwrap(SessionFactory.class).getSchemaManager().validateMappedObjects();
            assertThat(payments.findById(PAYMENT_ID)).isPresent();
            assertThat(cancellations.findByClaimId(42)).isPresent();
            assertThat(jdbc.queryForObject("select count(*) from payment.order_reconciliation_cursor", Long.class)).isEqualTo(lanes);
            processor.enqueue(PAYMENT_ID);
            assertThat(processor.acquire(PAYMENT_ID)).isNull();
        } finally {
            // Normal cleanup expects the metadata table to exist; a DDL failure remains a failed test.
            assertThat(jdbc.queryForObject("select to_regclass('payment.payment_auto_cancel') is not null", Boolean.class)).isTrue();
        }
    }
}
