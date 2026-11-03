package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.support.OrderReturnPersistenceContract;
import com.commercefoundry.domainlayer.order.support.OrderReturnTestConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
class OrderReturnPostgresTest extends OrderReturnPersistenceContract {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    @Test
    void competingDifferentKeysCannotConsumeLastQuantityTwice() throws Exception {
        Order order = create(OrderStatus.DELIVERED, 1);
        UUID first = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        withUncommittedReturn(order, first, () -> error(() -> facade.request(7, order.id(), other, command(order, 1)), OrderErrorCode.ORDER_CONCURRENTLY_CHANGED));
        error(() -> facade.request(7, order.id(), other, command(order, 1)), OrderErrorCode.RETURN_QUANTITY_EXCEEDED);
        assertThat(count("claim", order.id())).isEqualTo(1);
    }

    @Test
    void sameKeyRaceThenRetryReplaysOneClaim() throws Exception {
        Order order = create(OrderStatus.DELIVERED, 1);
        UUID key = UUID.randomUUID();
        withUncommittedReturn(order, key, () -> error(() -> facade.request(7, order.id(), key, command(order, 1)), OrderErrorCode.ORDER_CONCURRENTLY_CHANGED));
        assertThat(facade.request(7, order.id(), key, command(order, 1)).created()).isFalse();
        assertThat(count("claim", order.id())).isEqualTo(1);
    }

    private void withUncommittedReturn(Order order, UUID key, Runnable competitor) throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> winner = pool.submit(() -> tx().executeWithoutResult(s -> {
                facade.request(7, order.id(), key, command(order, 1));
                written.countDown();
                await(release);
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            competitor.run();
            release.countDown();
            winner.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void returnCommitDefeatsStalePurchaseConfirmation() throws Exception {
        Order order = create(OrderStatus.DELIVERED, 1);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> stale = pool.submit(() -> assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
                orders.findByIdAndMemberId(order.id(), 7).orElseThrow();
                read.countDown();
                await(returned);
                historyProcessor.confirmPurchase(7, order.id());
            })).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class));
            assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            facade.request(7, order.id(), UUID.randomUUID(), command(order, 1));
            returned.countDown();
            stale.get(10, TimeUnit.SECONDS);
        } finally {
            returned.countDown();
            pool.shutdownNow();
        }
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(count("order_status_history", order.id())).isEqualTo(2);
    }

    @Test
    void refreshSeesConfirmedStateDespiteStalePersistenceContext() throws Exception {
        Order order = create(OrderStatus.DELIVERED, 1);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch confirmed = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> stale = pool.submit(() -> assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
                orders.findByIdAndMemberId(order.id(), 7).orElseThrow();
                loaded.countDown();
                await(confirmed);
                facade.request(7, order.id(), UUID.randomUUID(), command(order, 1));
            })).isInstanceOfSatisfying(com.commercefoundry.domainlayer.order.application.exception.OrderException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID)));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            historyProcessor.confirmPurchase(7, order.id());
            confirmed.countDown();
            stale.get(10, TimeUnit.SECONDS);
        } finally {
            confirmed.countDown();
            pool.shutdownNow();
        }
        assertThat(count("claim", order.id())).isZero();
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.PURCHASE_CONFIRMED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Competing transaction did not arrive");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
