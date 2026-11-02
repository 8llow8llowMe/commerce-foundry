package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStatusHistoryRepository;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAutoConfirmationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


/** 자동 구매 확정의 실제 JPQL · 이력 원자성 · 반품/중복 경합 계약. */
public abstract class OrderAutoConfirmationPersistenceContract {
    protected static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final AtomicLong IDS = new AtomicLong(800000);
    @MockitoSpyBean protected OrderRepositoryPort orders;
    @Autowired protected OrderAutoConfirmationProcessor processor;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @MockitoSpyBean protected OrderStatusHistoryRepository histories;

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private long create(Instant delivered, OrderStatus status) {
        long id = IDS.incrementAndGet();
        var order = Order.place(id, 7, domesticQuote(quoteLine(101, 1000, 1)), IDS::incrementAndGet, address("KR"), IDS.incrementAndGet(), NOW);
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            jdbc.update("update orders.orders set status = ?, delivered_at = ?, version = version + 1 where id = ?",
                status.name(), delivered == null ? null : java.sql.Timestamp.from(delivered), id);
        });
        return id;
    }
    private Order read(long id) { return tx().execute(s -> orders.findById(id).orElseThrow()); }
    private long historyCount(long id) {
        return jdbc.queryForObject("select count(*) from orders.order_status_history where order_id = ?", Long.class, id);
    }

    @Test
    void keysetFiltersStatusNullFutureAndIncludesExactBoundary() {
        Instant cutoff = NOW.minusSeconds(604800);
        long a = create(cutoff.minusSeconds(1), OrderStatus.DELIVERED);
        long b = create(cutoff, OrderStatus.DELIVERED);
        long c = create(cutoff, OrderStatus.DELIVERED);
        create(cutoff.plusNanos(1000), OrderStatus.DELIVERED);
        create(null, OrderStatus.DELIVERED);
        for (OrderStatus status : OrderStatus.values()) {
            if (status != OrderStatus.DELIVERED) { create(cutoff, status); }
        }
        var first = processor.findDue(cutoff, null, 2);
        assertThat(first).extracting(row -> row.orderId()).containsExactly(a, b);
        assertThat(processor.findDue(cutoff, first.getLast(), 2)).extracting(row -> row.orderId()).containsExactly(c);
    }

    @Test
    void confirmationIsIndependentAtomicAndIdempotent() {
        long id = create(NOW, OrderStatus.DELIVERED);
        Order before = read(id);
        tx().executeWithoutResult(outer -> {
            assertThat(processor.confirm(id, NOW)).isTrue();
            outer.setRollbackOnly();
        });
        assertThat(processor.confirm(id, NOW)).isFalse();
        Order after = read(id);
        assertThat(after.status()).isEqualTo(OrderStatus.PURCHASE_CONFIRMED);
        assertThat(after.deliveredAt()).isEqualTo(before.deliveredAt());
        assertThat(after.quote()).isEqualTo(before.quote());
        assertThat(after.shippingAddress()).isEqualTo(before.shippingAddress());
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(historyCount(id)).isEqualTo(2);
        var row = jdbc.queryForMap("select changed_by_type, changed_by_id, reason from orders.order_status_history where order_id = ? and to_status = 'PURCHASE_CONFIRMED'", id);
        assertThat(row.get("changed_by_type")).isEqualTo("SYSTEM");
        assertThat(row.get("changed_by_id")).isNull();
        assertThat(row.get("reason")).isEqualTo("AUTO_PURCHASE_CONFIRMATION");
    }

    @Test
    void processorRechecksCandidateStateAndTimestamp() {
        assertThat(processor.confirm(create(null, OrderStatus.DELIVERED), NOW)).isFalse();
        assertThat(processor.confirm(create(NOW.plusSeconds(1), OrderStatus.DELIVERED), NOW)).isFalse();
        for (OrderStatus status : OrderStatus.values()) {
            if (status != OrderStatus.DELIVERED) {
                assertThat(processor.confirm(create(NOW, status), NOW)).as(status.name()).isFalse();
            }
        }
        assertThat(processor.confirm(Long.MAX_VALUE, NOW)).isFalse();
    }
    @Test
    @DisplayName("자동 확정가 읽은 직후 반품 요청가 커밋되면 자동 확정는 버전 충돌로 롤백된다")
    void concurrentReturnCommitFencesConfirmationAfterDeliveredRead() throws Exception {
        long id = create(NOW, OrderStatus.DELIVERED);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        OrderRepositoryPort target = AopTestUtils.getUltimateTargetObject(orders);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            loaded.countDown();
            if (!returned.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Return writer did not complete");
            }
            return result;
        }).when(target).findById(id);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> confirmation = pool.submit(() -> {
                try {
                    return processor.confirm(id, NOW);
                } catch (OptimisticLockingFailureException expected) {
                    return false;
                }
            });
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            tx().executeWithoutResult(status -> assertThat(jdbc.update(
                "update orders.orders set status = 'RETURN_REQUESTED', version = version + 1 where id = ? and version = 1 and status = 'DELIVERED'", id)).isEqualTo(1));
            returned.countDown();
            assertThat(confirmation.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            returned.countDown();
            pool.shutdownNow();
            reset(target);
        }
        assertThat(read(id).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(historyCount(id)).isEqualTo(1);

    }

    @Test
    @DisplayName("두 인스턴스가 같은 주문을 동시에 자동 확정하면 하나만 커밋해 확정 이력이 하나다")
    void twoConcurrentConfirmationsWriteOneHistory() throws Exception {
        long id = create(NOW, OrderStatus.DELIVERED);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothRead = new CountDownLatch(2);
        OrderRepositoryPort target = AopTestUtils.getUltimateTargetObject(orders);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            bothRead.countDown();
            if (!bothRead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Competing reader did not arrive");
            }
            return result;
        }).when(target).findById(id);
        try {
            Callable<Boolean> action = () -> {
                try {
                    return processor.confirm(id, NOW);
                } catch (OptimisticLockingFailureException ignored) {
                    return false;
                }
            };
            Future<Boolean> a = pool.submit(action);
            Future<Boolean> b = pool.submit(action);
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
            reset(target);
        }
        assertThat(historyCount(id)).isEqualTo(2);

    }

    @Test
    @DisplayName("이력 저장이 실패하면 이미 flush 한 상태도 롤백된다")
    void historyFailureRollsBackAlreadyFlushedState() {
        long id = create(NOW, OrderStatus.DELIVERED);
        OrderStatusHistoryRepository target = AopTestUtils.getUltimateTargetObject(histories);
        doThrow(new IllegalStateException("SimulatedHistoryFailure")).when(target).save(any());
        try {
            assertThatThrownBy(() -> processor.confirm(id, NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(read(id).status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(historyCount(id)).isEqualTo(1);

        } finally {
            reset(target);
        }
    }

}
