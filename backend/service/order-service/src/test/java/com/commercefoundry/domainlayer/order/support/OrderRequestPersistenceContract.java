package com.commercefoundry.domainlayer.order.support;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRequestItemRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRequestRepository;
import com.commercefoundry.domainlayer.order.application.port.out.query.PendingProductQuantityQueryResult;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRepository;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderRequestProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequestItem;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
    protected static final Duration MARGIN = Duration.ofSeconds(10);
    protected static final String HASH = "a".repeat(64);
    protected static final long PRODUCT = 7101;
    protected static final List<OrderRequestItem> ITEMS = List.of(new OrderRequestItem(PRODUCT, 1));
    private static final AtomicLong IDS = new AtomicLong(10_000);
    @Autowired protected OrderRequestRepositoryPort requests;
    @Autowired protected OrderRequestRepository rows;
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected OrderRepository orderRows;
    @Autowired protected OrderRequestItemRepository itemRows;
    @Autowired protected PlatformTransactionManager transactions;

    protected abstract boolean postgres();

    protected long id() { return IDS.incrementAndGet(); }

    protected OrderRequest saved(UUID key, long member) {
        return requests.insertIfAbsent(OrderRequest.start(id(), member, key, HASH, id(), NOW, LEASE), ITEMS).orElseThrow();
    }

    protected TransactionTemplate tx() {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    protected OrderRequestProcessor processor(Instant now) {
        return new OrderRequestProcessor(requests, new SnowflakeIdGenerator(1, 3), new OrderProperties(new OrderProperties.Creation(LEASE, 5, Duration.ofMinutes(30), MARGIN)),
            Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void activeRequestCountIsPerMemberBoundedBySinceAndExcludesOwnRecord() {
        long member = id();
        Instant before = Instant.now().minusSeconds(60);
        var own = saved(UUID.randomUUID(), member);
        saved(UUID.randomUUID(), member);
        saved(UUID.randomUUID(), id());

        // 삽입 때 updated_at 은 감사(시스템 시각)로 채워진다 — 상태와 무관하게 회원 · 활동 시각 범위로만 센다
        assertThat(requests.countActiveSince(member, before, 0)).isEqualTo(2);
        assertThat(requests.countActiveSince(member, before, own.id())).isEqualTo(1);
        Instant later = Instant.now().plusSeconds(60);
        assertThat(requests.countActiveSince(member, later, 0)).isZero();
        assertThat(requests.countActiveSince(id(), before, 0)).isZero();

        // 창 밖이 된 기록을 이어받으면 활동 시각이 갱신돼 다시 센다 — 휴면 기록을 모아 한꺼번에 이어받아 상한을 넘는 경로를 막는다
        var takenOver = requests.takeOver(own, later.plusSeconds(1), LEASE).orElseThrow();
        assertThat(requests.countActiveSince(member, later, 0)).isEqualTo(1);
        assertThat(requests.countActiveSince(member, later, takenOver.id())).isZero();
    }

    @Test
    void duplicateInsertRollsBackBeforeFreshReadAndMembersHaveIndependentKeys() {
        UUID key = UUID.randomUUID();
        var original = saved(key, id());
        var duplicate = OrderRequest.start(id(), original.memberId(), key, HASH, id(), NOW, LEASE);
        if (postgres()) {
            assertThat(requests.insertIfAbsent(duplicate, ITEMS)).isEmpty();
        } else {
            assertThatThrownBy(() -> requests.insertIfAbsent(duplicate, ITEMS)).isInstanceOf(DataIntegrityViolationException.class);
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
            return requests.insertIfAbsent(request, ITEMS).isPresent();
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
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(original, NOW.plus(LEASE), MARGIN)))
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
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(forged, NOW, MARGIN))).isInstanceOf(OrderException.class);
    }

    @Test
    void completionRequiresCallerTransactionAndValidLiveOwnership() {
        var request = saved(UUID.randomUUID(), id());
        assertThatThrownBy(() -> requests.completeOwned(request, NOW, MARGIN)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(request, request.leaseExpiresAt(), MARGIN)))
            .isInstanceOf(OrderException.class);
        tx().executeWithoutResult(status -> requests.completeOwned(request, NOW, MARGIN));
        var replay = processor(NOW.plusSeconds(100)).check(request.memberId(), request.idempotencyKey(), HASH);
        assertThat(replay.isReplay()).isTrue();
        assertThat(replay.orderId()).isEqualTo(request.orderId());
        assertThat(requests.endLease(request, NOW)).isFalse();
    }

    /**
     * 완료 margin (#436). 경계 시나리오 — A 가 lease 끝(L) 직전에 완료 UPDATE 를 통과하고 커밋이 L 뒤로 밀리면, 그 사이 B 의 수량 계수는 (1) 에서 A 의 기록을
     * "lease 끝남" 으로 빼고 (2) 에서 커밋 전 A 의 주문을 못 봐 A 를 어디에도 세지 않는다 — 둘 다 통과해 상한을 넘는다. 그래서 완료는 lease 가
     * margin(저장 timeout + 시계 차 여유) 넘게 남았을 때만 한다 — 완료 커밋은 늘 L 전에 끝나거나 롤백된다.
     */
    @Test
    void completionIsRejectedInsideTheMarginBeforeLeaseEnd() {
        var request = saved(UUID.randomUUID(), id());
        Instant leaseEnd = request.leaseExpiresAt();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(request, leaseEnd.minus(MARGIN), MARGIN)))
            .as("lease 가 정확히 margin 남으면 거절").isInstanceOfSatisfying(OrderException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS));
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(request, leaseEnd.minusSeconds(1), MARGIN)))
            .isInstanceOf(OrderException.class);
        assertThat(requests.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey()).orElseThrow().status())
            .isEqualTo(OrderRequest.Status.PROCESSING);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> requests.completeOwned(request, NOW, Duration.ZERO)))
            .isInstanceOf(IllegalArgumentException.class);

        tx().executeWithoutResult(status -> requests.completeOwned(request, leaseEnd.minus(MARGIN).minusNanos(1_000), MARGIN));
        assertThat(requests.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey()).orElseThrow().status())
            .as("margin 보다 1µs 더 남으면 완료").isEqualTo(OrderRequest.Status.COMPLETED);
    }

    @Test
    void completionAndC1InsertRollBackTogetherAndCanCommitTogether() {
        var request = saved(UUID.randomUUID(), id());
        var order = orderFor(request);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            requests.completeOwned(request, NOW, MARGIN);
            orders.insert(order, OrderStatusHistory.created(id(), order));
            throw new IllegalStateException("simulated final failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(requests.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey()).orElseThrow().status())
            .isEqualTo(OrderRequest.Status.PROCESSING);
        assertThat(tx().<Boolean>execute(status -> orderRows.existsById(order.id()))).isFalse();
        tx().executeWithoutResult(status -> {
            requests.completeOwned(request, NOW, MARGIN);
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
            try { requests.completeOwned(request, NOW.plus(LEASE), MARGIN); } catch (OrderException expected) { /* caller must not commit */ }
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
        assertThatThrownBy(() -> processor.acquire(id(), request.idempotencyKey(), HASH, checked, ITEMS)).isInstanceOf(IllegalArgumentException.class);
        var acquired = processor.acquire(request.memberId(), request.idempotencyKey(), HASH, checked, ITEMS);
        assertThat(acquired.orderId()).isEqualTo(request.orderId());
        processor.endLeaseQuietly(acquired.request());
        assertThat(processor.check(request.memberId(), request.idempotencyKey(), HASH).outcome()).isEqualTo(OrderRequestClaim.Outcome.TAKE_OVER);
    }

    @Test
    void requestAndItemsCommitTogetherAndKeyConflictRollsBackItems() {
        long member = id();
        UUID key = UUID.randomUUID();
        var original = requests.insertIfAbsent(OrderRequest.start(id(), member, key, HASH, id(), NOW, LEASE),
            List.of(new OrderRequestItem(PRODUCT, 3), new OrderRequestItem(PRODUCT + 1, 1))).orElseThrow();
        assertThat(itemsOf(original.id())).containsExactlyInAnyOrderEntriesOf(Map.of(PRODUCT, 3, PRODUCT + 1, 1));

        var duplicate = OrderRequest.start(id(), member, key, HASH, id(), NOW, LEASE);
        if (postgres()) {
            assertThat(requests.insertIfAbsent(duplicate, List.of(new OrderRequestItem(PRODUCT, 5)))).isEmpty();
        } else {
            assertThatThrownBy(() -> requests.insertIfAbsent(duplicate, List.of(new OrderRequestItem(PRODUCT, 5))))
                .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(itemsOf(duplicate.id())).as("기록이 지면 항목도 함께 롤백된다").isEmpty();
        assertThat(itemsOf(original.id())).containsExactlyInAnyOrderEntriesOf(Map.of(PRODUCT, 3, PRODUCT + 1, 1));

        var sameProductTwice = OrderRequest.start(id(), member, UUID.randomUUID(), HASH, id(), NOW, LEASE);
        assertThatThrownBy(() -> requests.insertIfAbsent(sameProductTwice, List.of(new OrderRequestItem(PRODUCT, 1), new OrderRequestItem(PRODUCT, 2))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requests.insertIfAbsent(sameProductTwice, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests.findByMemberIdAndIdempotencyKey(member, sameProductTwice.idempotencyKey())).isEmpty();
    }

    @Test
    void inFlightQuantitiesCountOnlyOtherLiveProcessingRequestsOfTheMemberInTheWindow() {
        long member = id();
        // 감사가 채우는 updated_at 은 시스템 시각, lease · 완료 · 종료 시각은 NOW 다 — 창은 둘 다 담게 넓게 잡아 상태 · lease 조건만 가른다
        Instant since = NOW.minus(Duration.ofHours(1));
        var own = inserted(member, Map.of(PRODUCT, 2));
        inserted(member, Map.of(PRODUCT, 3, PRODUCT + 1, 4));
        var ended = inserted(member, Map.of(PRODUCT, 5));
        assertThat(requests.endLease(ended, NOW)).isTrue();
        var completed = inserted(member, Map.of(PRODUCT, 7));
        tx().executeWithoutResult(status -> requests.completeOwned(completed, NOW, MARGIN));
        inserted(id(), Map.of(PRODUCT, 11));
        inserted(member, Map.of(PRODUCT + 2, 13));

        assertThat(inFlight(member, List.of(PRODUCT, PRODUCT + 1), own.id(), NOW, since))
            .as("자기 기록 · lease 종료 · 완료 · 다른 회원 · 묻지 않은 상품은 빠진다").containsExactlyInAnyOrderEntriesOf(Map.of(PRODUCT, 3L, PRODUCT + 1, 4L));
        assertThat(inFlight(member, List.of(PRODUCT, PRODUCT + 1), 0, NOW, since)).containsExactlyInAnyOrderEntriesOf(Map.of(PRODUCT, 5L, PRODUCT + 1, 4L));
        assertThat(inFlight(member, List.of(PRODUCT), 0, NOW.plus(LEASE), since)).as("lease 가 끝나면 세지 않는다").isEmpty();
        assertThat(inFlight(member, List.of(PRODUCT), 0, NOW, Instant.now().plusSeconds(60))).as("창 밖은 세지 않는다").isEmpty();
        assertThat(inFlight(member, List.of(), 0, NOW, since)).isEmpty();
    }

    @Test
    void takeOverKeepsItemsAndBringsTheRequestBackIntoTheWindow() {
        long member = id();
        var original = inserted(member, Map.of(PRODUCT, 2));
        Instant later = Instant.now().plusSeconds(60);
        assertThat(inFlight(member, List.of(PRODUCT), 0, later, later.minusSeconds(1))).isEmpty();

        var takenOver = requests.takeOver(original, later, LEASE).orElseThrow();
        assertThat(itemsOf(takenOver.id())).containsExactlyInAnyOrderEntriesOf(Map.of(PRODUCT, 2));
        assertThat(inFlight(member, List.of(PRODUCT), 0, later, later.minusSeconds(1))).containsExactlyInAnyOrderEntriesOf(Map.of(PRODUCT, 2L));
        assertThat(inFlight(member, List.of(PRODUCT), takenOver.id(), later, later.minusSeconds(1))).isEmpty();
    }

    private OrderRequest inserted(long member, Map<Long, Integer> quantities) {
        List<OrderRequestItem> items = quantities.entrySet().stream().map(entry -> new OrderRequestItem(entry.getKey(), entry.getValue())).toList();
        return requests.insertIfAbsent(OrderRequest.start(id(), member, UUID.randomUUID(), HASH, id(), NOW, LEASE), items).orElseThrow();
    }

    private Map<Long, Long> inFlight(long member, Collection<Long> productIds, long excludedRequestId, Instant now, Instant since) {
        return requests.sumInFlightQuantitiesByProduct(member, productIds, excludedRequestId, now, since).stream()
            .collect(Collectors.toMap(PendingProductQuantityQueryResult::productId, PendingProductQuantityQueryResult::quantity));
    }

    private Map<Long, Integer> itemsOf(long requestId) {
        return tx().execute(status -> itemRows.findAll().stream().filter(item -> item.getOrderRequestId() == requestId)
            .collect(Collectors.toMap(item -> item.getProductId(), item -> item.getQuantity())));
    }

    private Order orderFor(OrderRequest request) {
        var quote = OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101L, 1000, 1));
        return Order.place(request.orderId(), request.memberId(), quote, this::id, OrderFixtures.address(OrderFixtures.KR), id(), NOW.plusSeconds(120));
    }
}
