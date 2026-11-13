package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelExternalRefundResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Channel;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationExternalRefundUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationUseCase;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgRetentionQueryResult;
import com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationExternalRefundFacade;
import com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel.Status;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.global.properties.PaymentCancelExternalRefundProperties;
import com.commercefoundry.global.properties.PaymentCancelVoidProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 외부 확인 환불 기록(#693)을 실제 DB 트랜잭션 · 결제 행 잠금으로 본다 — 엄격 INSERT · 전이 표 UPDATE · 결제 상태 전이, 기록 뒤 결제 후 취소 준비가 PG 를
 * 다시 부르지 않음(이중 환불 회귀), PG 조회 사이 원장 변경, 낙관 락 012, 같은 요청 · 결제 후 취소 준비와의 경합. PG 는 포트만 가짜다.
 */
@DataJpaTest
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentMapperImpl.class, PaymentRepositoryAdapter.class,
    PaymentCancelPersistenceAdapter.class, PaymentCancelProcessor.class, PaymentCancelExternalRefundProcessor.class, PaymentGatewayProcessor.class,
    PaymentCancellationFacade.class, PaymentCancellationExternalRefundFacade.class, PaymentInternalPresenter.class,
    PaymentCancelExternalRefundContract.ExternalRefundConfig.class})
@TestPropertySource(properties = {"spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class PaymentCancelExternalRefundContract {

    static final long AMOUNT = 10_000;

    @Autowired PaymentRepositoryPort payments;
    @Autowired PaymentCancelPersistencePort cancellations;
    @Autowired PaymentCancelProcessor orderCancellations;
    @Autowired PaymentCancellationUseCase orderFacade;
    @Autowired PaymentCancellationExternalRefundUseCase externalRefunds;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGatewayPort gateway;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from payment.payment_cancel");
        jdbc.update("delete from payment.payment");
    }

    void save(com.commercefoundry.domainlayer.payment.domain.model.Payment payment) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> payments.save(payment));
    }

    void row(long claimId, Status status) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.save(new PaymentCancel(claimId, PAYMENT_ID, krw(AMOUNT), status)));
    }

    /** 행의 생성 · 수정 시각을 과거로 돌린다 — 14일 + 여유 · 거절 유예가 지난 상태를 만든다. */
    void backdate(long claimId, Duration age) {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minus(age);
        jdbc.update("update payment.payment_cancel set created_at = ?, updated_at = ? where claim_id = ?", at, at, claimId);
    }

    PaymentCancelExternalRefundResponse record(long claimId, Channel channel) {
        return externalRefunds.recordExternalRefund(PAYMENT_ID, ORDER_ID, claimId, AMOUNT, CurrencyCode.KRW, channel);
    }

    /** PG 잔액이 원장 기대보다 이만큼 줄어 있다. 다시 스텁해도 앞 답이 돌지 않게 willAnswer().given() 으로 건다. */
    void pgRefundedOutside(long unexplained) {
        willAnswer(call -> {
            Money expected = call.getArgument(1);
            Money observed = expected.minus(krw(unexplained));
            return observed.equals(expected) ? PgRetentionQueryResult.matched(observed) : PgRetentionQueryResult.mismatched(observed);
        }).given(gateway).inquireRetention(any(), any());
    }

    Status statusOf(long claimId) {
        return cancellations.findByClaimId(claimId).orElseThrow().status();
    }

    PaymentStatus paymentStatus() {
        return payments.findById(PAYMENT_ID).orElseThrow().status();
    }

    @Test
    void pgChannelRecordMovesPaymentAndPrepareNeverCallsPgAgain() {
        save(done());
        pgRefundedOutside(AMOUNT);

        PaymentCancelExternalRefundResponse recorded = record(42, Channel.PG);
        PaymentCancelExternalRefundResponse again = record(42, Channel.PG);

        assertThat(recorded.outcome()).isEqualTo("RECORDED");
        assertThat(recorded.reason()).isEqualTo("PG_VERIFIED");
        assertThat(again.reason()).isEqualTo("CLAIM_ALREADY_RECORDED");
        assertThat(again.channel()).isEqualTo("PG");
        assertThat(statusOf(42)).isEqualTo(Status.EXTERNAL_PG_REFUNDED);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        // 재요청은 PG 를 다시 조회하지 않는다
        verify(gateway, times(1)).inquireRetention(any(), any());

        // 이중 환불 회귀 — 같은 claimId 의 결제 후 취소는 PG 없이 200 이고 원장 · 결제를 바꾸지 않는다
        var refund = orderFacade.cancel(PAYMENT_ID, 42, AMOUNT, CurrencyCode.KRW, "42");
        assertThat(refund.claimId()).isEqualTo("42");
        verify(gateway, never()).cancel(any(), anyLong(), any(), any());
        assertThat(statusOf(42)).isEqualTo(Status.EXTERNAL_PG_REFUNDED);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        // 다른 클레임의 누계는 외부 PG 환불을 포함한다
        assertThat(orderCancellations.prepare(PAYMENT_ID, 43, krw(119_000)).cumulative()).isEqualTo(krw(129_000));
    }

    @Test
    void offPgRecordKeepsPaymentStatusAndCountsTowardRefundLimit() {
        save(done());
        pgRefundedOutside(0);

        PaymentCancelExternalRefundResponse recorded = record(42, Channel.OFF_PG);

        assertThat(recorded.outcome()).isEqualTo("RECORDED");
        assertThat(recorded.channel()).isEqualTo("OFF_PG");
        assertThat(recorded.paymentStatus().code()).isEqualTo("DONE");
        assertThat(statusOf(42)).isEqualTo(Status.OFF_PG_REFUNDED);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.DONE);
        orderFacade.cancel(PAYMENT_ID, 42, AMOUNT, CurrencyCode.KRW, "42");
        verify(gateway, never()).cancel(any(), anyLong(), any(), any());
        // 한도(017)는 PG 밖 환불을 포함한다 — 결제액 전부를 다른 클레임으로 다시 준비할 수 없다
        assertThatThrownBy(() -> orderCancellations.prepare(PAYMENT_ID, 43, krw(129_000)))
            .isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID));
    }

    void rejectedRow(long claimId) {
        row(claimId, Status.PENDING);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.save(cancellations.findByClaimId(claimId).orElseThrow().rejected()));
    }

    static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, PaymentErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void existingRowsMoveOnlyAlongTransitionTable() {
        save(done());
        rejectedRow(41);
        backdate(41, Duration.ofMinutes(5));
        pgRefundedOutside(0);

        // 유예 지난 REJECTED → PG 밖 채널 — 결제 상태는 그대로
        assertThat(record(41, Channel.OFF_PG).outcome()).isEqualTo("RECORDED");
        assertThat(statusOf(41)).isEqualTo(Status.OFF_PG_REFUNDED);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.DONE);

        // 14일 + 여유가 지난 PENDING → PG 밖 채널 — 결제 상태는 그대로
        row(40, Status.PENDING);
        backdate(40, Duration.ofDays(14).plusHours(2));
        assertThat(record(40, Channel.OFF_PG).outcome()).isEqualTo("RECORDED");
        assertThat(statusOf(40)).isEqualTo(Status.OFF_PG_REFUNDED);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.DONE);

        // 유예 지난 REJECTED → PG 채널 — 결제 상태가 PARTIAL_CANCELED 로 간다
        rejectedRow(39);
        backdate(39, Duration.ofMinutes(5));
        pgRefundedOutside(AMOUNT);
        assertThat(record(39, Channel.PG).outcome()).isEqualTo("RECORDED");
        assertThat(statusOf(39)).isEqualTo(Status.EXTERNAL_PG_REFUNDED);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);

        // 14일 + 여유가 지난 PENDING → PG 채널
        row(42, Status.PENDING);
        backdate(42, Duration.ofDays(14).plusHours(2));
        assertThat(record(42, Channel.PG).outcome()).isEqualTo("RECORDED");
        assertThat(statusOf(42)).isEqualTo(Status.EXTERNAL_PG_REFUNDED);
        assertThat(payments.findById(PAYMENT_ID).orElseThrow().status()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);

        // VOIDED → PG 채널은 되고, VOIDED → OFF_PG 는 저장 어댑터가 전이 표로 거부한다(016)
        new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.insertVoided(PaymentCancel.voided(43, PAYMENT_ID, krw(AMOUNT))));
        assertCode(() -> cancellations.recordExternalRefund(new PaymentCancel(43, PAYMENT_ID, krw(AMOUNT), Status.OFF_PG_REFUNDED)),
            PaymentErrorCode.CANCELLATION_CONFLICT);
        assertThat(record(43, Channel.PG).outcome()).isEqualTo("RECORDED");
        assertThat(statusOf(43)).isEqualTo(Status.EXTERNAL_PG_REFUNDED);
        // 종결 행은 되돌리지 않는다
        for (Status back : new Status[] {Status.PENDING, Status.SUCCEEDED}) {
            assertCode(() -> cancellations.save(new PaymentCancel(43, PAYMENT_ID, krw(AMOUNT), back)), PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        assertCode(() -> cancellations.recordExternalRefund(new PaymentCancel(43, PAYMENT_ID, krw(AMOUNT), Status.OFF_PG_REFUNDED)),
            PaymentErrorCode.CANCELLATION_CONFLICT);
        assertThat(statusOf(43)).isEqualTo(Status.EXTERNAL_PG_REFUNDED);
    }

    @Test
    void genericSaveRefusesExternalRefundStatuses() {
        save(done());
        row(42, Status.PENDING);

        for (Status external : new Status[] {Status.EXTERNAL_PG_REFUNDED, Status.OFF_PG_REFUNDED}) {
            assertThatThrownBy(() -> cancellations.save(new PaymentCancel(42, PAYMENT_ID, krw(AMOUNT), external))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cancellations.save(new PaymentCancel(44, PAYMENT_ID, krw(AMOUNT), external))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(statusOf(42)).isEqualTo(Status.PENDING);
        assertThat(cancellations.findByClaimId(44)).isEmpty();
    }

    @Test
    void recentRejectionOfOtherClaimBlocksRecord() {
        save(done());
        rejectedRow(41);
        pgRefundedOutside(AMOUNT);

        PaymentCancelExternalRefundResponse result = record(42, Channel.PG);

        assertThat(result.outcome()).isEqualTo("UNCONFIRMED");
        assertThat(result.reason()).isEqualTo("OTHER_CANCEL_PENDING");
        assertThat(cancellations.findByClaimId(42)).isEmpty();
        verify(gateway, never()).inquireRetention(any(), any());

        // 유예가 지나면 막지 않는다
        backdate(41, Duration.ofMinutes(5));
        assertThat(record(42, Channel.PG).outcome()).isEqualTo("RECORDED");
    }

    @Test
    void otherClaimRejectedDuringPgInquiryLeavesLedgerUnwritten() {
        save(done());
        willAnswer(call -> {
            // PG 조회 사이 다른 클레임이 준비(PENDING) → PG 거절(REJECTED)로 끝난다 — 합계는 같지만 거절 금액이 PG 에 반영됐을 수 있다
            orderCancellations.prepare(PAYMENT_ID, 41, krw(5_000));
            orderCancellations.reject(PAYMENT_ID, 41);
            Money expected = call.getArgument(1);
            return PgRetentionQueryResult.mismatched(expected.minus(krw(AMOUNT)));
        }).given(gateway).inquireRetention(any(), any());

        PaymentCancelExternalRefundResponse result = record(42, Channel.PG);

        assertThat(result.outcome()).isEqualTo("UNCONFIRMED");
        assertThat(result.reason()).isEqualTo("LEDGER_CHANGED");
        assertThat(statusOf(41)).isEqualTo(Status.REJECTED);
        assertThat(cancellations.findByClaimId(42)).isEmpty();
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void twoClaimsCannotBothAbsorbOneConsoleRefund() throws Exception {
        save(done());
        // PG 잔액은 고정이다 — 콘솔에서 10,000 한 건만 환불됐다
        willAnswer(call -> PgRetentionQueryResult.mismatched(krw(129_000 - AMOUNT))).given(gateway).inquireRetention(any(), any());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<PaymentCancelExternalRefundResponse> first = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return record(42, Channel.PG);
            };
            Callable<PaymentCancelExternalRefundResponse> second = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return record(43, Channel.PG);
            };
            var a = pool.submit(first);
            var b = pool.submit(second);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));

            assertThat(results).filteredOn(r -> r.outcome().equals("RECORDED")).hasSize(1);
            assertThat(results).filteredOn(r -> r.outcome().equals("UNCONFIRMED"))
                .singleElement().satisfies(r -> assertThat(r.reason()).isIn("LEDGER_CHANGED", "PG_LEDGER_MISMATCH"));
            assertThat(cancellations.findByPaymentId(PAYMENT_ID)).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(Status.EXTERNAL_PG_REFUNDED));
            assertThat(payments.findById(PAYMENT_ID).orElseThrow().status()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        } finally {
            start.countDown();
        }
    }

    @Test
    void freshPendingIsRefusedWithoutPg() {
        save(done());
        row(42, Status.PENDING);
        backdate(42, Duration.ofDays(14).plusMinutes(30));

        PaymentCancelExternalRefundResponse result = record(42, Channel.PG);

        assertThat(result.outcome()).isEqualTo("UNCONFIRMED");
        assertThat(result.reason()).isEqualTo("CLAIM_REFUND_PENDING");
        assertThat(statusOf(42)).isEqualTo(Status.PENDING);
        verify(gateway, never()).inquireRetention(any(), any());
    }

    @Test
    void claimPreparedDuringPgInquiryLeavesLedgerUnwritten() {
        save(done());
        given(gateway.inquireRetention(any(), any())).willAnswer(call -> {
            // PG 조회는 트랜잭션 밖이다 — 그 사이 다른 클레임의 결제 후 취소 준비가 PENDING 을 커밋한다
            orderCancellations.prepare(PAYMENT_ID, 41, krw(5_000));
            Money expected = call.getArgument(1);
            return PgRetentionQueryResult.mismatched(expected.minus(krw(AMOUNT)));
        });

        PaymentCancelExternalRefundResponse result = record(42, Channel.PG);

        assertThat(result.outcome()).isEqualTo("UNCONFIRMED");
        assertThat(result.reason()).isEqualTo("LEDGER_CHANGED");
        assertThat(cancellations.findByClaimId(42)).isEmpty();
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void staleRowUpdateLosesOptimisticLockWith012() {
        save(done());
        row(42, Status.PENDING);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.save(cancellations.findByClaimId(42).orElseThrow().rejected()));

        TransactionTemplate outer = new TransactionTemplate(transactions);
        TransactionTemplate other = new TransactionTemplate(transactions);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> outer.executeWithoutResult(tx -> {
            PaymentCancel loaded = cancellations.findByClaimId(42).orElseThrow();
            // 결제 행 잠금 밖의 쓰기가 같은 행의 버전을 올렸다
            other.executeWithoutResult(inner -> jdbc.update("update payment.payment_cancel set version = version + 1 where claim_id = 42"));
            cancellations.recordExternalRefund(loaded.externallyRefunded(Status.OFF_PG_REFUNDED));
        })).isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT));
        assertThat(statusOf(42)).isEqualTo(Status.REJECTED);
    }

    @Test
    void recordForAnotherClaimOfOtherPaymentIsRefusedWith019() {
        save(done());
        save(ready(PAYMENT_ID + 1, ORDER_ID + 1));
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
            cancellations.save(new PaymentCancel(42, PAYMENT_ID + 1, krw(AMOUNT), Status.PENDING)));

        assertThatThrownBy(() -> record(42, Channel.PG))
            .isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_KEY_REUSED));
        verify(gateway, never()).inquireRetention(any(), any());
    }

    @Test
    void concurrentSameRequestsRecordOnce() throws Exception {
        pgRefundedOutside(AMOUNT);
        for (long claimId = 52; claimId < 57; claimId++) {
            cleanup();
            save(done());
            long claim = claimId;
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<PaymentCancelExternalRefundResponse> recording = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return record(claim, Channel.PG);
                };
                var first = pool.submit(recording);
                var second = pool.submit(recording);
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                // 결제 행 잠금으로 줄을 서므로 뒤 요청의 tx2 는 앞 요청이 커밋한 행을 보고 같은 기록으로 답한다 — 012 · 두 번 기록이 없다
                assertThat(first.get(15, TimeUnit.SECONDS).outcome()).isEqualTo("RECORDED");
                assertThat(second.get(15, TimeUnit.SECONDS).outcome()).isEqualTo("RECORDED");
                assertThat(cancellations.findByPaymentId(PAYMENT_ID)).singleElement()
                    .satisfies(row -> assertThat(row.status()).isEqualTo(Status.EXTERNAL_PG_REFUNDED));
                assertThat(payments.findById(PAYMENT_ID).orElseThrow().status()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
            } finally {
                start.countDown();
            }
        }
    }

    @Test
    void concurrentRecordAndPrepareEndInExactlyOneSafeState() throws Exception {
        pgRefundedOutside(AMOUNT);
        for (long claimId = 62; claimId < 67; claimId++) {
            cleanup();
            save(done());
            long claim = claimId;
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<PaymentCancelExternalRefundResponse> recording = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return record(claim, Channel.PG);
                };
                Callable<PaymentCancel> preparing = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return orderCancellations.prepare(PAYMENT_ID, claim, krw(AMOUNT)).cancel();
                };
                var recordResult = pool.submit(recording);
                var prepareResult = pool.submit(preparing);
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                PaymentCancelExternalRefundResponse decision = recordResult.get(15, TimeUnit.SECONDS);
                PaymentCancel prepared = prepareResult.get(15, TimeUnit.SECONDS);
                Status row = statusOf(claim);

                if (row == Status.EXTERNAL_PG_REFUNDED) {
                    // 기록이 먼저 — 준비는 PG 로 갈 PENDING 을 만들지 않고 환불 확정 행을 그대로 넘긴다(Facade 는 PG 없이 200)
                    assertThat(decision.outcome()).isEqualTo("RECORDED");
                    assertThat(prepared.status()).isEqualTo(Status.EXTERNAL_PG_REFUNDED);
                } else {
                    // 준비가 먼저 — PENDING 이 남고 기록은 미확정(대기 중 · 원장 변경)이다
                    assertThat(row).isEqualTo(Status.PENDING);
                    assertThat(decision.outcome()).isEqualTo("UNCONFIRMED");
                    assertThat(decision.reason()).isIn("CLAIM_REFUND_PENDING", "LEDGER_CHANGED");
                    assertThat(paymentStatus()).isEqualTo(PaymentStatus.DONE);
                }
            } finally {
                start.countDown();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalRefundConfig {
        @Bean Clock clock() {
            return Clock.systemUTC();
        }

        @Bean PaymentCancelVoidProperties paymentCancelVoidProperties() {
            return new PaymentCancelVoidProperties(Duration.ofSeconds(60));
        }

        @Bean PaymentCancelExternalRefundProperties paymentCancelExternalRefundProperties() {
            return new PaymentCancelExternalRefundProperties(null);
        }
    }
}
