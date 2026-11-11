package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelVoidResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationVoidUseCase;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgRetentionQueryResult;
import com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationFacade;
import com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationVoidFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelVoidProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.global.properties.PaymentCancelVoidProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 무환불 봉인(#691)을 실제 DB 트랜잭션 · 결제 행 잠금으로 본다 — VOIDED 엄격 INSERT, 봉인 뒤 결제 후 취소 준비의 영구 거절, PG 조회 사이 원장 변경,
 * void 와 결제 후 취소 준비의 경합. PG 는 포트만 가짜다.
 */
@DataJpaTest
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentMapperImpl.class, PaymentRepositoryAdapter.class,
    PaymentCancelPersistenceAdapter.class, PaymentCancelProcessor.class, PaymentCancelVoidProcessor.class, PaymentGatewayProcessor.class,
    PaymentCancellationFacade.class, PaymentCancellationVoidFacade.class, PaymentInternalPresenter.class, PaymentCancelVoidContract.VoidConfig.class})
@TestPropertySource(properties = {"spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class PaymentCancelVoidContract {

    @Autowired PaymentRepositoryPort payments;
    @Autowired PaymentCancelPersistencePort cancellations;
    @Autowired PaymentCancelProcessor orderCancellations;
    @Autowired PaymentCancellationUseCase orderFacade;
    @Autowired PaymentCancellationVoidUseCase voidFacade;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGatewayPort gateway;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from payment.payment_cancel");
        jdbc.update("delete from payment.payment");
    }

    void save(Payment payment) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> payments.save(payment));
    }

    PaymentCancelVoidResponse callVoid(long claimId) {
        return voidFacade.voidCancellation(PAYMENT_ID, ORDER_ID, claimId, 10_000, CurrencyCode.KRW);
    }

    void pgMatches() {
        given(gateway.inquireRetention(any(), any())).willReturn(PgRetentionQueryResult.of(PgRetentionQueryResult.State.MATCHED));
    }

    @Test
    void sealedClaimIsPersistedOnceAndPrepareIsPermanentlyRefusedWithoutPg() {
        save(done());
        pgMatches();

        PaymentCancelVoidResponse first = callVoid(42);
        PaymentCancelVoidResponse again = callVoid(42);

        assertThat(first.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
        assertThat(first.sealed()).isTrue();
        assertThat(again).isEqualTo(first);
        assertThat(cancellations.findByPaymentId(PAYMENT_ID)).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(PaymentCancel.Status.VOIDED);
            assertThat(row.amount()).isEqualTo(krw(10_000));
            assertThat(row.updatedAt()).isNotNull();
        });
        assertThatThrownBy(() -> orderFacade.cancel(PAYMENT_ID, 42, 10_000, CurrencyCode.KRW, "42"))
            .isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT));
        verify(gateway, never()).cancel(any(), anyLong(), any(), any());
        assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.VOIDED);

        // VOIDED 금액은 취소 누계에 들어가지 않는다 — 다른 클레임은 결제액 전부를 준비할 수 있다
        var prepared = orderCancellations.prepare(PAYMENT_ID, 43, krw(129_000));
        assertThat(prepared.cumulative()).isEqualTo(krw(129_000));
    }

    @Test
    void strictVoidedInsertNeverOverwritesExistingClaim() {
        save(done());
        cancellations.save(new PaymentCancel(42, PAYMENT_ID, krw(10_000), PaymentCancel.Status.PENDING));

        assertThatThrownBy(() -> cancellations.insertVoided(PaymentCancel.voided(42, PAYMENT_ID, krw(10_000))))
            .isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT));
        assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
    }

    @Test
    void claimPreparedDuringPgInquiryLeavesLedgerUnsealed() {
        save(done());
        given(gateway.inquireRetention(any(), any())).willAnswer(call -> {
            // PG 조회는 트랜잭션 밖이다 — 그 사이 같은 클레임의 결제 후 취소 준비가 PENDING 을 커밋한다
            orderCancellations.prepare(PAYMENT_ID, 42, krw(10_000));
            return PgRetentionQueryResult.of(PgRetentionQueryResult.State.MATCHED);
        });

        PaymentCancelVoidResponse result = callVoid(42);

        assertThat(result.outcome()).isEqualTo("UNCONFIRMED");
        assertThat(result.reason()).isEqualTo("LEDGER_CHANGED");
        assertThat(result.sealed()).isFalse();
        assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
    }

    @Test
    void concurrentVoidAndPrepareEndInExactlyOneSafeState() throws Exception {
        pgMatches();
        for (long claimId = 42; claimId < 47; claimId++) {
            cleanup();
            save(done());
            long claim = claimId;
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<PaymentCancelVoidResponse> voiding = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return callVoid(claim);
                };
                Callable<PaymentException> preparing = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        orderCancellations.prepare(PAYMENT_ID, claim, krw(10_000));
                        return null;
                    } catch (PaymentException refused) {
                        return refused;
                    }
                };
                var voidResult = pool.submit(voiding);
                var prepareResult = pool.submit(preparing);
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                PaymentCancelVoidResponse decision = voidResult.get(15, TimeUnit.SECONDS);
                PaymentException refusal = prepareResult.get(15, TimeUnit.SECONDS);
                PaymentCancel.Status row = cancellations.findByClaimId(claim).orElseThrow().status();

                if (row == PaymentCancel.Status.VOIDED) {
                    assertThat(decision.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
                    assertThat(refusal).isNotNull().extracting(PaymentException::getErrorCode).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT);
                } else {
                    assertThat(row).isEqualTo(PaymentCancel.Status.PENDING);
                    assertThat(decision.outcome()).isEqualTo("UNCONFIRMED");
                    assertThat(decision.sealed()).isFalse();
                    assertThat(refusal).isNull();
                }
            } finally {
                start.countDown();
            }
        }
    }

    @Test
    void voidSealedByAnotherVoidDuringPgInquiryAnswersSameDecisionWithoutCollision() {
        save(done());
        var nested = new java.util.concurrent.atomic.AtomicBoolean();
        PaymentCancelVoidResponse[] inner = new PaymentCancelVoidResponse[1];
        given(gateway.inquireRetention(any(), any())).willAnswer(call -> {
            // 첫 void 의 PG 조회 사이에 같은 클레임의 두 번째 void 가 끝까지 돌아 VOIDED 를 커밋한다
            if (nested.compareAndSet(false, true)) {
                inner[0] = callVoid(42);
            }
            return PgRetentionQueryResult.of(PgRetentionQueryResult.State.MATCHED);
        });

        PaymentCancelVoidResponse outer = callVoid(42);

        assertThat(inner[0].outcome()).isEqualTo("NO_REFUND_CONFIRMED");
        assertThat(outer.outcome()).isEqualTo("NO_REFUND_CONFIRMED");
        assertThat(outer.reason()).isEqualTo("CLAIM_NEVER_REQUESTED");
        assertThat(outer.sealed()).isTrue();
        assertThat(cancellations.findByPaymentId(PAYMENT_ID)).singleElement()
            .satisfies(row -> assertThat(row.status()).isEqualTo(PaymentCancel.Status.VOIDED));
    }

    @Test
    void concurrentVoidsOfSameClaimBothConfirmWithOneSealRow() throws Exception {
        pgMatches();
        for (long claimId = 52; claimId < 57; claimId++) {
            cleanup();
            save(done());
            long claim = claimId;
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<PaymentCancelVoidResponse> voiding = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return callVoid(claim);
                };
                var first = pool.submit(voiding);
                var second = pool.submit(voiding);
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                // 같은 결제 행 잠금으로 줄을 서므로 뒤 void 의 tx2 는 앞 void 가 커밋한 VOIDED 를 보고 같은 판정을 답한다 — 012 가 나지 않는다
                assertThat(first.get(15, TimeUnit.SECONDS).outcome()).isEqualTo("NO_REFUND_CONFIRMED");
                assertThat(second.get(15, TimeUnit.SECONDS).outcome()).isEqualTo("NO_REFUND_CONFIRMED");
                assertThat(cancellations.findByPaymentId(PAYMENT_ID)).singleElement()
                    .satisfies(row -> assertThat(row.status()).isEqualTo(PaymentCancel.Status.VOIDED));
            } finally {
                start.countDown();
            }
        }
    }

    @Test
    void voidForAnotherOrdersPaymentIsRefusedWithoutWrite() {
        save(done());
        pgMatches();

        assertThatThrownBy(() -> voidFacade.voidCancellation(PAYMENT_ID, ORDER_ID + 1, 42, 10_000, CurrencyCode.KRW))
            .isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_KEY_REUSED));
        assertThat(cancellations.findByClaimId(42)).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class VoidConfig {
        @Bean Clock clock() {
            return Clock.systemUTC();
        }

        @Bean PaymentCancelVoidProperties paymentCancelVoidProperties() {
            return new PaymentCancelVoidProperties(Duration.ofSeconds(60));
        }
    }
}
