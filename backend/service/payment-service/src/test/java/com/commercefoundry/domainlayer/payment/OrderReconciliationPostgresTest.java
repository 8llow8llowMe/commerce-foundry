package com.commercefoundry.domainlayer.payment;

import static com.commercefoundry.domainlayer.payment.OrderPaymentReplyUnitTest.command;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.domainlayer.payment.application.service.OrderReconciliationSchedulerFacade;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class OrderReconciliationPostgresTest extends OrderReconciliationContract {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");

    @ParameterizedTest @CsvSource({"PAID,true", "PAID,false", "REJECTED_ORDER_CANCELED,true", "REJECTED_ORDER_CANCELED,false"})
    void syntheticAndRealEventsRaceOnTheSamePaymentAndRetryConverges(Result result, boolean syntheticWins) throws Exception {
        result(result);
        var event = command(result);
        var barrier = new CyclicBarrier(2);
        var committed = new CountDownLatch(1);
        afterRead = p -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                boolean synthetic = Thread.currentThread().getName().equals("synthetic-reconciliation");
                if (synthetic != syntheticWins) assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
            }
            catch (Exception failure) { throw new IllegalStateException("Barrier failed"); }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var real = pool.submit(() -> {
                Thread.currentThread().setName("real-reply");
                try { facade.handle(event); return "COMMITTED"; }
                catch (OrderPaymentReplyException failure) { return failure.getReason().name(); }
                finally { if (!syntheticWins) committed.countDown(); }
            });
            var synthetic = pool.submit(() -> {
                Thread.currentThread().setName("synthetic-reconciliation");
                try { reconciliation.reconcile(); }
                finally { if (syntheticWins) committed.countDown(); }
            });
            assertThat(real.get(20, TimeUnit.SECONDS)).isEqualTo(syntheticWins ? "PAYMENT_VERSION" : "COMMITTED");
            synthetic.get(20, TimeUnit.SECONDS);
        } finally { afterRead = p -> { }; }
        assertThat(fresh().version()).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
        var counter = meters.find(com.commercefoundry.domainlayer.payment.application.service.OrderReconciliationObservation.CHANGED).counter();
        if (syntheticWins) assertThat(counter.count()).isEqualTo(1);
        else assertThat(counter).isNull();
        // Reenter the synthetic route after an optimistic loser. Same group, distinct event IDs.
        var source = processor.fetchSource(PAYMENT_ID);
        processor.record(new com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand(
            OrderReconciliationSchedulerFacade.syntheticEventId(PAYMENT_ID), event.kind(), PAYMENT_ID, ORDER_ID, event.rejectionReason()), source);
        facade.handle(event);
        assertThat(inboxCount()).isEqualTo(2);
        assertThat(fresh().version()).isEqualTo(1);
        verify(policy, atMost(2)).onRejected(PAYMENT_ID); // fast attempt plus independent DB lane may both run
    }
}
