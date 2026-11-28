package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimCancellationRejectionProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.support.ClaimCancellationRejectionContract;
import com.commercefoundry.domainlayer.order.support.OrderReturnTestConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 커밋된 스냅숏({@code ddl/order-schema.sql})의 PostgreSQL 에서 같은 계약 + 주문 행 잠금(NOWAIT) 경합을 확인한다 (#17 A4) — 취소 + 거절 + 결정의 직전 실패 코드가
 * claim check 제약({@code ck_claim_admin_decision} · {@code ck_claim_cancellation_next_attempt} · {@code ck_claim_cancellation_failure_code} ·
 * {@code ck_claim_cancellation_payment})을 통과하는지와 주문을 쥔 다른 트랜잭션과의 경합은 H2 로 대신할 수 없다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@Import({OrderReturnTestConfig.class, ClaimCancellationRejectionProcessor.class})
class ClaimCancellationRejectionPostgresTest extends ClaimCancellationRejectionContract {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    /** 다른 트랜잭션(고객 요청 · 환불 기록 · 다른 운영자)이 주문을 쥔 동안 거절하면 기다리지 않고 409 ORDER_037 이고, 풀린 뒤 같은 화면으로 다시 보내면 끝난다. */
    @Test
    void rejectionLosesToConcurrentOrderLockWithoutChangesThenSucceeds() throws Exception {
        Claim failed = rejectedRefund(OrderStatus.PAID);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> tx().executeWithoutResult(status -> {
                orders.findByIdForUpdate(failed.orderId()).orElseThrow();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> rejection.reject(failed.id(), failed.version(), REASON, ADMIN)).isInstanceOfSatisfying(OrderException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED));
            // NOWAIT 이면 곧바로 진다 — 기다렸다면 트랜잭션 제한 시간(5초) 뒤에야 실패한다
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).as("주문 잠금은 기다리지 않는다").isLessThan(Duration.ofSeconds(2));
            // 자격 확인은 잠그지 않아 잠금 중에도 통과한다(무환불 판정은 그 뒤 트랜잭션 밖이다)
            assertThat(rejection.prepare(failed.id(), failed.version()).id()).isEqualTo(failed.id());

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(readClaim(failed.id())).as("잠금에서 진 거절은 아무것도 바꾸지 않는다").isEqualTo(failed);
        assertThat(read(failed.orderId()).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(adminHistories(failed.orderId())).isEmpty();

        assertThat(rejection.reject(failed.id(), failed.version(), REASON, ADMIN).status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(read(failed.orderId()).status()).isEqualTo(OrderStatus.PAID);
    }
}
