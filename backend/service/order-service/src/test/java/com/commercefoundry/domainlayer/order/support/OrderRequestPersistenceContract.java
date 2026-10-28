package com.commercefoundry.domainlayer.order.support;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRequestRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRepository;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderRequestProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 같은 저장 계약을 H2와 PostgreSQL에서 실행한다. 제약 오류 이름 판정은 PostgreSQL 전용이다. */
public abstract class OrderRequestPersistenceContract {
    protected static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final String HASH = "a".repeat(64);
    private static final AtomicLong IDS = new AtomicLong(10_000);
    @Autowired protected OrderRequestRepositoryPort requests;
    @Autowired protected OrderRequestRepository rows;
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected OrderRepository orderRows;
    @Autowired protected PlatformTransactionManager transactions;

    protected abstract boolean postgres();

    protected long id() { return IDS.incrementAndGet(); }

    protected OrderRequest saved(UUID key, long member) {
        return requests.insertIfAbsent(OrderRequest.start(id(), member, key, HASH, id(), NOW, LEASE)).orElseThrow();
    }

    protected TransactionTemplate tx() {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    protected OrderRequestProcessor processor(Instant now) {
        return new OrderRequestProcessor(requests, new SnowflakeIdGenerator(1, 3), new OrderProperties(new OrderProperties.Creation(LEASE)),
            Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void duplicateInsertRollsBackBeforeFreshReadAndMembersHaveIndependentKeys() {
        UUID key = UUID.randomUUID();
        var original = saved(key, id());
        var duplicate = OrderRequest.start(id(), original.memberId(), key, HASH, id(), NOW, LEASE);
        if (postgres()) {
            assertThat(requests.insertIfAbsent(duplicate)).isEmpty();
        } else {
            assertThatThrownBy(() -> requests.insertIfAbsent(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(requests.findByMemberIdAndIdempotencyKey(original.memberId(), key)).contains(original);
        var other = saved(key, id());
        assertThat(other.orderId()).isNotEqualTo(original.orderId());
        assertThat(requests.findByMemberIdAndIdempotencyKey(id(), key)).isEmpty();
    }

    @Test
    void concurrentInsertHasOneWinnerAndOneRecord() throws Exception {
        long member = id();
        UUID key = UUID.randomUUID();
        var first = OrderRequest.start(id(), member, key, HASH, id(), NOW, LEASE);
        var second = OrderRequest.start(id(), member, key, HASH, id(), NOW, LEASE);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> insertAfter(start, first));
            var two = pool.submit(() -> insertAfter(start, second));
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(requests.findByMemberIdAndIdempotencyKey(member, key).orElseThrow().orderId()).isIn(first.orderId(), second.orderId());
    }

    private boolean insertAfter(CountDownLatch start, OrderRequest request) throws Exception {
        start.await();
        try {
            return requests.insertIfAbsent(request).isPresent();
        } catch (DataIntegrityViolationException conflict) {
            assertThat(postgres()).isFalse();
            assertThat(conflict.getMostSpecificCause()).isInstanceOf(java.sql.SQLException.class);
            assertThat(((java.sql.SQLException) conflict.getMostSpecificCause()).getSQLState()).isEqualTo("23505");
            assertThat(conflict.getMostSpecificCause().getMessage()).contains("uk_order_request_member_id_idempotency_key");
            return false;
        }
    }

    @Test
    void oneTakeoverWinsAndStaleOwnerCannotEndOrComplete() throws Exception {
        var original = saved(UUID.randomUUID(), id());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return requests.takeOver(original, NOW.plus(LEASE), LEASE); });
            var two = pool.submit(() -> { start.await(); return requests.takeOver(original, NOW.plus(LEASE), LEASE); });
            start.countDown();
            var results = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(requests.endLease(original, NOW.plus(LEASE))).isFalse();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(original, NOW.plus(LEASE))))
            .isInstanceOf(OrderException.class);
        var winner = requests.findByMemberIdAndIdempotencyKey(original.memberId(), original.idempotencyKey()).orElseThrow();
        assertThat(winner.version()).isEqualTo(original.version() + 1);
        assertThat(winner.orderId()).isEqualTo(original.orderId());
    }

    @Test
    void immutableBindingCannotBeForgedEvenWithCorrectVersion() {
        var original = saved(UUID.randomUUID(), id());
        var forged = new OrderRequest(original.id(), id(), original.idempotencyKey(), HASH, original.orderId(),
            original.status(), original.leaseExpiresAt(), original.version());
        assertThat(requests.endLease(forged, NOW)).isFalse();
        assertThat(requests.takeOver(forged, NOW.plus(LEASE), LEASE)).isEmpty();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(forged, NOW))).isInstanceOf(OrderException.class);
    }

    @Test
    void completionRequiresCallerTransactionAndValidLiveOwnership() {
        var request = saved(UUID.randomUUID(), id());
        assertThatThrownBy(() -> requests.completeOwned(request, NOW)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(request, request.leaseExpiresAt())))
            .isInstanceOf(OrderException.class);
        tx().executeWithoutResult(status -> requests.completeOwned(request, NOW));
        var replay = processor(NOW.plusSeconds(100)).check(request.memberId(), request.idempotencyKey(), HASH);
        assertThat(replay.isReplay()).isTrue();
        assertThat(replay.orderId()).isEqualTo(request.orderId());
        assertThat(requests.endLease(request, NOW)).isFalse();
    }

    @Test
    void completionAndC1InsertRollBackTogetherAndCanCommitTogether() {
        var request = saved(UUID.randomUUID(), id());
        var order = orderFor(request);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            requests.completeOwned(request, NOW);
            orders.insert(order, OrderStatusHistory.created(id(), order));
            throw new IllegalStateException("simulated final failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(requests.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey()).orElseThrow().status())
            .isEqualTo(OrderRequest.Status.PROCESSING);
        assertThat(tx().<Boolean>execute(status -> orderRows.existsById(order.id()))).isFalse();
        tx().executeWithoutResult(status -> {
            requests.completeOwned(request, NOW);
            orders.insert(order, OrderStatusHistory.created(id(), order));
        });
        assertThat(tx().<Boolean>execute(status -> orderRows.existsById(order.id()))).isTrue();
    }

    @Test
    void lostFenceMarksCallerRollbackOnlyEvenIfCallerCatchesConflict() {
        var request = saved(UUID.randomUUID(), id());
        requests.takeOver(request, NOW.plus(LEASE), LEASE).orElseThrow();
        var order = orderFor(request);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            try { requests.completeOwned(request, NOW.plus(LEASE)); } catch (OrderException expected) { /* caller must not commit */ }
            orders.insert(order, OrderStatusHistory.created(id(), order));
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(tx().<Boolean>execute(status -> orderRows.existsById(order.id()))).isFalse();
    }

    @Test
    void processorChecksDigestBusyBoundaryAndUnrelatedClaimBeforeAnyAcquisition() {
        var request = saved(UUID.randomUUID(), id());
        assertThatThrownBy(() -> processor(NOW).check(request.memberId(), request.idempotencyKey(), HASH))
            .isInstanceOfSatisfying(OrderException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS));
        assertThatThrownBy(() -> processor(NOW).check(request.memberId(), request.idempotencyKey(), "b".repeat(64)))
            .isInstanceOfSatisfying(OrderException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.IDEMPOTENCY_KEY_REUSED));
        var processor = processor(request.leaseExpiresAt());
        var checked = processor.check(request.memberId(), request.idempotencyKey(), HASH);
        assertThat(checked.outcome()).isEqualTo(OrderRequestClaim.Outcome.TAKE_OVER);
        assertThatThrownBy(() -> processor.acquire(id(), request.idempotencyKey(), HASH, checked)).isInstanceOf(IllegalArgumentException.class);
        var acquired = processor.acquire(request.memberId(), request.idempotencyKey(), HASH, checked);
        assertThat(acquired.orderId()).isEqualTo(request.orderId());
        processor.endLeaseQuietly(acquired.request());
        assertThat(processor.check(request.memberId(), request.idempotencyKey(), HASH).outcome()).isEqualTo(OrderRequestClaim.Outcome.TAKE_OVER);
    }

    private Order orderFor(OrderRequest request) {
        var quote = OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101L, 1000, 1));
        return Order.place(request.orderId(), request.memberId(), quote, this::id, OrderFixtures.address(OrderFixtures.KR), id(), NOW.plusSeconds(120));
    }
}
