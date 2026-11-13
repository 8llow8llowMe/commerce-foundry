package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.support.OrderReturnTestConfig;
import com.commercefoundry.domainlayer.order.support.ReturnRefundPipelineContract;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
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
 * 커밋된 스냅숏({@code ddl/order-schema.sql})의 PostgreSQL 에서 같은 계약 + 주문 행 잠금(NOWAIT) 경합을 확인한다 (#17 B2a) — 반품 check 제약 · 스케줄러 질의 ·
 * 반품 환불 기록이 주문 잠금에서 지는 경로(503 {@code ORDER_036} → 같은 클레임 재전송)는 H2 로 대신할 수 없다.
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
@Import(OrderReturnTestConfig.class)
class ReturnRefundPipelinePostgresTest extends ReturnRefundPipelineContract {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    /**
     * 고객 추가 접수(주문 FOR UPDATE)가 주문을 쥔 동안 온 반품 환불 성공은 기록 트랜잭션이 주문 잠금에서 바로 진다 — 클레임은 승인 그대로 남고 503 {@code ORDER_036} 이다.
     * 잠금이 풀린 뒤 같은 클레임으로 다시 보내면(스케줄러 경로) payment 는 같은 요청 200 이고 기록 · 주문 정리가 한 번에 끝난다.
     */
    @Test
    void refundRecordingLosesToConcurrentOrderLockThenSameClaimRecovers() throws Exception {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim approved = approve(requestReturn(order, 1, 0), order, 10_000, true, NOW);
        paymentAnswers(PaymentCancelQueryResult.succeeded());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> customer = executor.submit(() -> tx().executeWithoutResult(status -> {
                orders.findByIdForUpdate(order.id()).orElseThrow();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> pipeline.progress(approved)).isInstanceOfSatisfying(OrderException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.CANCELLATION_REFUND_RECORDING_UNAVAILABLE));
            assertThat(readClaim(approved.id())).as("기록하지 못한 성공은 클레임을 바꾸지 않는다").isEqualTo(approved);
            assertThat(read(order.id()).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);

            release.countDown();
            customer.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        Claim done = pipeline.progress(readClaim(approved.id()));

        assertThat(done.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.DELIVERED);
        verify(paymentCancelPort, times(2)).cancel(PAYMENT_ID, approved.id(), approved.expectedRefund());
    }
}
