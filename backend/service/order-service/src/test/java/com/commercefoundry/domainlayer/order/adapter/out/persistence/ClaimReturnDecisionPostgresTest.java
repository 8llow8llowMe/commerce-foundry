package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimReturnDecisionProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.Fault;
import com.commercefoundry.domainlayer.order.support.ClaimReturnDecisionContract;
import com.commercefoundry.domainlayer.order.support.OrderReturnTestConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * 커밋된 스냅숏({@code ddl/order-schema.sql})의 PostgreSQL 에서 같은 계약 + 주문 행 잠금(NOWAIT) 경합을 확인한다 (#17 B2c-2) — 반품 승인 check 제약
 * ({@code ck_claim_return_approval} · {@code ck_claim_return_fault} · {@code ck_claim_admin_decision})과 같은 주문의 동시 결정은 H2 로 대신할 수 없다.
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
@Import({OrderReturnTestConfig.class, ClaimReturnDecisionProcessor.class})
class ClaimReturnDecisionPostgresTest extends ClaimReturnDecisionContract {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    /** 다른 트랜잭션(같은 주문의 다른 반품 결정 · 고객 추가 접수)이 주문을 쥔 동안 {@code decide} 를 부른다 — 기다리지 않고 409 ORDER_037 이어야 한다. */
    private void whileOrderLocked(Order order, Consumer<Order> decide) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> tx().executeWithoutResult(status -> {
                orders.findByIdForUpdate(order.id()).orElseThrow();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> decide.accept(order)).isInstanceOfSatisfying(OrderException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED));
            // NOWAIT 이면 곧바로 진다 — 기다렸다면 트랜잭션 제한 시간(5초) 뒤에야 실패한다
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).as("주문 잠금은 기다리지 않는다").isLessThan(Duration.ofSeconds(2));

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void approvalAndRejectionLoseToConcurrentOrderLockWithoutChangesThenSucceed() throws Exception {
        Order order = returnRequestedOrder(true);
        Claim first = requestReturn(order, 0, ClaimItem.Reason.DEFECTIVE);
        Claim second = requestReturn(order, 1, ClaimItem.Reason.CHANGE_OF_MIND);
        long policy = previewPolicyAmount(first, Fault.SELLER);

        whileOrderLocked(order, locked -> approve(first, Fault.SELLER, true, 0));
        whileOrderLocked(order, locked -> decisions.reject(second.id(), second.version(), REASON, ADMIN));

        assertThat(readClaim(first.id())).as("잠금에서 진 승인은 아무것도 바꾸지 않는다").isEqualTo(first);
        assertThat(readClaim(second.id())).isEqualTo(second);
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);

        // 잠금이 풀리면 같은 화면(같은 버전 · 금액)으로 다시 보내 끝난다
        assertThat(approve(first, Fault.SELLER, true, 0).expectedRefund().amount()).isEqualTo(policy);
        assertThat(decisions.reject(second.id(), second.version(), REASON, ADMIN).status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(read(order.id()).status()).as("승인된 반품이 열려 있다").isEqualTo(OrderStatus.RETURN_REQUESTED);
    }
}
