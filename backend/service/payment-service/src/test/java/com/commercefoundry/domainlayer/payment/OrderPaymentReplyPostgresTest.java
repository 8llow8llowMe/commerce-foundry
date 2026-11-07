package com.commercefoundry.domainlayer.payment;

import static com.commercefoundry.domainlayer.payment.OrderPaymentReplyUnitTest.command;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class OrderPaymentReplyPostgresTest extends OrderPaymentReplyContract {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sameEventChangeAndBothNoOpContendOnVersionOrUnique(boolean noOp) throws Exception {
        if (noOp) {
            tx().executeWithoutResult(s -> payments.save(fresh().markOrderSynced()));
        }
        long version = fresh().version();
        var event = command(Result.PAID);
        var barrier = new CyclicBarrier(2);
        afterRead = p -> await(barrier);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> attempt(event));
            var b = pool.submit(() -> attempt(event));
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("COMMITTED", noOp ? "INBOX_DUPLICATE" : "PAYMENT_VERSION");
        } finally {
            afterRead = p -> { };
        }
        assertThat(inboxCount()).isEqualTo(1);
        assertThat(fresh().version()).isEqualTo(version + (noOp ? 0 : 1));
        assertThat(facade.handle(event).duplicate()).isTrue();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void differentEventsForSameResultContendAndRetryToNoOp(boolean noOp) throws Exception {
        result(Result.REJECTED_ORDER_CANCELED);
        if (noOp) {
            tx().executeWithoutResult(s -> payments.save(fresh().markOrderSyncFailed("ORDER_CANCELED")));
        }
        var a = command(Result.REJECTED_ORDER_CANCELED);
        var b = command(Result.REJECTED_ORDER_CANCELED);
        var barrier = new CyclicBarrier(2);
        afterRead = p -> await(barrier);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> attempt(a));
            var second = pool.submit(() -> attempt(b));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("COMMITTED", noOp ? "COMMITTED" : "PAYMENT_VERSION");
        } finally {
            afterRead = p -> { };
        }
        facade.handle(a);
        facade.handle(b);
        assertThat(inboxCount()).isEqualTo(2);
        assertThat(fresh().orderSyncFailureCode()).isEqualTo("ORDER_CANCELED");
        assertThat(fresh().version()).isEqualTo(1);
        verify(policy, times(noOp ? 0 : 1)).onRejected(PAYMENT_ID);
    }
    @ParameterizedTest @CsvSource({"10000,PAID", "129000,PAID", "10000,REJECTED_ORDER_CANCELED", "129000,REJECTED_ORDER_CANCELED"})
    void cancelWinsAgainstStaleReplyAndRetryExcludesFullCancellation(long amount, Result result) throws Exception {
        result(result);
        pendingCancel(amount);
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        afterRead = p -> { loaded.countDown(); await(release); };
        var event = command(result);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reply = pool.submit(() -> attempt(event));
            await(loaded);
            cancelProcessor.complete(PAYMENT_ID, 42);
            release.countDown();
            assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo("PAYMENT_VERSION");
        } finally {
            release.countDown();
            afterRead = p -> { };
        }
        assertThat(inboxCount()).isZero();
        assertThat(facade.handle(event).cancelCandidate()).isEqualTo(result != Result.PAID && amount < 129000);
        assertThat(fresh().status()).isEqualTo(amount < 129000 ? PaymentStatus.PARTIAL_CANCELED : PaymentStatus.CANCELED);
        assertThat(fresh().orderSynced()).isEqualTo(result == Result.PAID);
        assertThat(fresh().amount()).isEqualTo(krw(129000));
        verify(policy, times(result != Result.PAID && amount < 129000 ? 1 : 0)).onRejected(PAYMENT_ID);
    }
    @ParameterizedTest @CsvSource({"10000,PAID", "129000,PAID", "10000,REJECTED_ORDER_CANCELED", "129000,REJECTED_ORDER_CANCELED"})
    void replyWinsAndCancelReadsCommittedSyncWithoutOptimisticConflict(long amount, Result result) throws Exception {
        result(result);
        pendingCancel(amount);
        var flushed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelStarted = new CountDownLatch(1);
        afterSave = p -> { flushed.countDown(); await(release); };
        doAnswer(call -> { cancelStarted.countDown(); return call.callRealMethod(); }).when(cancels).lockPayment(PAYMENT_ID);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reply = pool.submit(() -> attempt(command(result)));
            await(flushed);
            // 취소의 save는 회신 전용 동기화 훅을 기다리지 않는다.
            afterSave = p -> { };
            var cancel = pool.submit(() -> cancelProcessor.complete(PAYMENT_ID, 42));
            await(cancelStarted);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String blocked = "select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%payment%'";
            while (jdbc.queryForObject(blocked, Long.class) == 0 && System.nanoTime() < deadline) { Thread.sleep(10); }
            assertThat(jdbc.queryForObject(blocked, Long.class)).isPositive();
            release.countDown();
            assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo("COMMITTED");
            assertThat(cancel.get(20, TimeUnit.SECONDS).orderSynced()).isEqualTo(result == Result.PAID);
        } finally {
            release.countDown();
            afterSave = p -> { };
        }
        assertThat(fresh().orderSynced()).isEqualTo(result == Result.PAID);
        assertThat(fresh().orderSyncFailureCode()).isEqualTo(result == Result.PAID ? null : "ORDER_CANCELED");
        assertThat(fresh().status()).isEqualTo(amount < 129000 ? PaymentStatus.PARTIAL_CANCELED : PaymentStatus.CANCELED);
        verify(policy, times(result == Result.PAID ? 0 : 1)).onRejected(PAYMENT_ID);
        assertThat(inboxCount()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(longs = {10000, 129000})
    void noOpReplyRacingCancelNeedsNoVersionWrite(long amount) throws Exception {
        tx().executeWithoutResult(s -> payments.save(fresh().markOrderSynced()));
        pendingCancel(amount);
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        afterRead = p -> { loaded.countDown(); await(release); };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reply = pool.submit(() -> attempt(command(Result.PAID)));
            await(loaded);
            cancelProcessor.complete(PAYMENT_ID, 42);
            release.countDown();
            assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo("COMMITTED");
        } finally {
            release.countDown();
            afterRead = p -> { };
        }
        assertThat(fresh().orderSynced()).isTrue();
        assertThat(inboxCount()).isEqualTo(1);
    }
    String attempt(OrderPaymentReplyCommand event) {
        try {
            facade.handle(event);
            return "COMMITTED";
        } catch (OrderPaymentReplyException e) {
            return e.getReason().name();
        }
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
