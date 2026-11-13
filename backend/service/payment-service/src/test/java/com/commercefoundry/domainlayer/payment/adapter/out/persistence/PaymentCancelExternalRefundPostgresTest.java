package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Channel;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel.Status;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 외부 확인 환불 기록 계약(#693)을 PostgreSQL 로 돌린다 — 결제 행 {@code PESSIMISTIC_WRITE} 줄 서기 · 엄격 INSERT · 낙관 락 · status check 6값(스냅숏).
 * 여기에만 있는 시험: 서로 다른 결제가 같은 claimId 를 동시에 INSERT 하면 유니크 인덱스 대기 뒤 한쪽이 409 {@code PAYMENT_012} 다
 * (H2 는 미커밋 키 대기 동작이 달라 두지 않는다).
 */
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment"})
class PaymentCancelExternalRefundPostgresTest extends PaymentCancelExternalRefundContract {

    private static final long OTHER_PAYMENT = PAYMENT_ID + 1;
    private static final String OTHER_PAYMENT_KEY = "tgen_20261021000000wxyzQRST";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");

    @MockitoSpyBean PaymentRepositoryAdapter paymentWrites;

    @Test
    void sameClaimInsertedByTwoPaymentsAtOnceLeavesOneRowAndOne012() throws Exception {
        save(done());
        save(ready(OTHER_PAYMENT, ORDER_ID + 1).startApproval(OTHER_PAYMENT_KEY, "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", STARTED_AT)
            .approve(approval(OTHER_PAYMENT_KEY, krw(129_000))));
        pgRefundedOutside(AMOUNT);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        // 첫 기록(PAYMENT_ID)은 INSERT 를 flush 한 뒤 결제 상태 저장에서 멈춘다 — 커밋 전이라 두 번째 기록은 행을 보지 못하고 같은 키로 INSERT 하다 대기한다
        willAnswer(call -> {
            Payment payment = call.getArgument(0);
            if (payment.id() == PAYMENT_ID && payment.status() == PaymentStatus.PARTIAL_CANCELED) {
                firstInserted.countDown();
                releaseFirst.await(10, TimeUnit.SECONDS);
            }
            return call.callRealMethod();
        }).given(paymentWrites).save(any());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> record(42, Channel.PG));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                try {
                    externalRefunds.recordExternalRefund(OTHER_PAYMENT, ORDER_ID + 1, 42, AMOUNT, CurrencyCode.KRW, Channel.PG);
                    return null;
                } catch (PaymentException refused) {
                    return refused.getErrorCode();
                }
            });
            // 두 번째 기록이 유니크 키(첫 기록의 미커밋 행)에서 잠금 대기에 들어간 것을 확인한 뒤 첫 기록을 커밋한다
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (lockWaiters() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(lockWaiters()).as("두 번째 INSERT 가 잠금 대기 중").isPositive();
            releaseFirst.countDown();

            assertThat(first.get(15, TimeUnit.SECONDS).outcome()).isEqualTo("RECORDED");
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
        } finally {
            releaseFirst.countDown();
        }
        assertThat(cancellations.findByClaimId(42).orElseThrow().paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(statusOf(42)).isEqualTo(Status.EXTERNAL_PG_REFUNDED);
        assertThat(cancellations.findByPaymentId(OTHER_PAYMENT)).isEmpty();
    }

    private int lockWaiters() {
        Integer waiting = jdbc.queryForObject(
            "select count(*)::int from pg_stat_activity where wait_event_type = 'Lock' and query ilike '%payment_cancel%'", Integer.class);
        return waiting == null ? 0 : waiting;
    }
}
