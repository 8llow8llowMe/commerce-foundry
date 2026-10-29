package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.out.event.OrderEventAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStatusHistoryRepository;
import com.commercefoundry.domainlayer.order.application.model.OrderExpiryCandidate;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.OrderSchedulerFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderExpiryProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.global.messaging.OutboxRepository;
import com.commercefoundry.global.properties.OrderPaymentExpiryProperties;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.outbox.OutboxStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 미결제 만료 영속성 계약 — H2({@code OrderExpiryH2Test}) · PostgreSQL({@code OrderExpiryPostgresTest})이 같은 단언을 돌린다.
 * 실제 트랜잭션으로 상태 · 이력 · outbox 가 함께 커밋 · 롤백되는지, 키셋 조회, 동시 결제 · 중복 만료의 버전 충돌, outbox 저장소 SPI 를 본다.
 */
public abstract class OrderExpiryPersistenceContract {

    protected static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final Path CONTRACT_DOCUMENT = Path.of("..", "..", "docs", "order-flow-guide.md");
    private static final String CONTRACT_SECTION = "### 3-5.";
    private static final AtomicLong IDS = new AtomicLong(500000);

    @MockitoSpyBean protected OrderRepositoryPort orders;
    @Autowired protected OrderExpiryProcessor processor;
    @Autowired protected OutboxRepository outbox;
    @Autowired protected OutboxStore store;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @MockitoSpyBean protected OrderEventAdapter events;
    @MockitoSpyBean protected OrderStatusHistoryRepository histories;

    protected long create(Instant due, OrderStatus status) {
        long id = IDS.incrementAndGet();
        var order = Order.place(id, 7, domesticQuote(quoteLine(101, 1000, 1)), IDS::incrementAndGet, address("KR"), IDS.incrementAndGet(), due);
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            if (status != OrderStatus.PENDING_PAYMENT) {
                jdbc.update("update orders.orders set status = ?, version = version + 1 where id = ?", status.name(), id);
            }
        });
        return id;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private Order read(long id) {
        return tx().execute(s -> orders.findById(id).orElseThrow());
    }

    private long historyCount(long id) {
        return jdbc.queryForObject("select count(*) from orders.order_status_history where order_id = ?", Long.class, id);
    }

    private long eventCount(long id) {
        return outbox.findAll().stream().filter(e -> e.getAggregateId().equals(Long.toString(id))).count();
    }

    /** 감사 시각은 메모리 값이 아니라 DB 에 저장된 값으로 본다 — merge 가 생성 시각을 덮지 않았는지 확인한다. */
    private Instant timestampColumn(String column, long id) {
        return jdbc.queryForObject("select " + column + " from orders.orders where id = ?", OffsetDateTime.class, id).toInstant();
    }

    /**
     * {@code order-flow-guide.md} §3-5 가 이벤트 계약 정본이다 — product 리스너 테스트({@code OrderStockReleaseEventListenerTest})가 같은 블록을 읽어 역직렬화한다.
     * 여기서는 order-service 가 실제로 outbox 에 넣은 봉투 · payload 가 그 정본과 같은지 본다 — 양쪽이 같은 문서에 묶여 한쪽만 바뀌면 깨진다.
     */
    private static void assertMatchesContractDocument(EventEnvelope envelope) throws IOException {
        List<String> lines = Files.readAllLines(CONTRACT_DOCUMENT, StandardCharsets.UTF_8);
        int section = indexOf(lines, CONTRACT_SECTION, 0);
        String envelopeRow = lines.get(indexOf(lines, "| 봉투 |", section));
        assertThat(envelopeRow).contains(
            "`eventType` = `" + envelope.eventType() + "`",
            "`aggregateType` = `" + envelope.aggregateType() + "`",
            "`schemaVersion` = " + envelope.schemaVersion(),
            "`producer` = `" + envelope.producer() + "`"
        );
        int start = indexOf(lines, "```json", section) + 1;
        JsonNode contract = new ObjectMapper().readTree(String.join("\n", lines.subList(start, indexOf(lines, "```", start))));
        JsonNode actual = envelope.payload();
        assertThat(fieldNames(actual)).isEqualTo(fieldNames(contract));
        assertThat(fieldNames(contract)).allSatisfy(name -> assertThat(actual.get(name).getNodeType()).isEqualTo(contract.get(name).getNodeType()));
        assertThat(actual.get("reason")).isEqualTo(contract.get("reason"));
    }

    private static Set<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private static int indexOf(List<String> lines, String prefix, int from) {
        for (int index = from; index < lines.size(); index++) {
            if (lines.get(index).strip().startsWith(prefix)) {
                return index;
            }
        }
        throw new AssertionError(CONTRACT_DOCUMENT.toAbsolutePath() + " 에서 [" + prefix + "] 를 찾지 못했다 — 계약 절(§3-5)의 봉투 행과 json 블록이 있어야 한다");
    }

    @Test
    @DisplayName("기한과 같은 시각에 만료한다 — 스냅숏은 그대로, 이력 · outbox 는 한 번만 남고 메시징이 꺼져도 outbox 에 쌓인다")
    void expiryAtExactDeadlinePreservesSnapshotsAndRecordsContractOnceWithMessagingDisabled() throws IOException {
        long id = create(NOW, OrderStatus.PENDING_PAYMENT);
        Order before = read(id);
        Instant createdAt = timestampColumn("created_at", id);
        Instant updatedAt = timestampColumn("updated_at", id);
        assertThat(processor.expire(id, NOW)).isTrue();
        assertThat(processor.expire(id, NOW)).isFalse();
        assertThat(timestampColumn("created_at", id)).isEqualTo(createdAt);
        assertThat(timestampColumn("updated_at", id)).isAfter(updatedAt);
        Order after = read(id);
        assertThat(after.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.quote()).isEqualTo(before.quote());
        assertThat(after.shippingAddress()).isEqualTo(before.shippingAddress());
        assertThat(after.itemIds()).isEqualTo(before.itemIds());
        assertThat(historyCount(id)).isEqualTo(2);
        assertThat(eventCount(id)).isEqualTo(1);
        var row = outbox.findAll().stream().filter(e -> e.getAggregateId().equals(Long.toString(id))).findFirst().orElseThrow();
        var json = new EventEnvelopeCodec(65536).decode(row.getPayload());
        assertThat(json.producer()).isEqualTo("order");
        assertThat(json.eventType()).isEqualTo("order-expired");
        assertThat(json.aggregateType()).isEqualTo("order");
        assertThat(json.aggregateId()).isEqualTo(Long.toString(id));
        assertThat(json.schemaVersion()).isEqualTo(1);
        assertThat(json.payload().size()).isEqualTo(3);
        assertThat(json.payload().path("orderId").asString()).isEqualTo(Long.toString(id));
        assertThat(json.payload().path("reservationId").asString()).isEqualTo(Long.toString(before.stockReservationId()));
        assertThat(json.payload().path("reason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        assertMatchesContractDocument(json);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(jdbc.queryForObject("select changed_by_type from orders.order_status_history where order_id = ? and to_status = 'EXPIRED'", String.class, id))
            .isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("기한 전 · 결제 완료 · 없는 주문은 바꾸지 않는다")
    void skipsFuturePaidAndAbsentOrders() {
        long future = create(NOW.plusSeconds(1), OrderStatus.PENDING_PAYMENT);
        long paid = create(NOW.minusSeconds(1), OrderStatus.PAID);
        assertThat(processor.expire(future, NOW)).isFalse();
        assertThat(processor.expire(paid, NOW)).isFalse();
        assertThat(processor.expire(Long.MAX_VALUE, NOW)).isFalse();
        assertThat(read(paid).status()).isEqualTo(OrderStatus.PAID);
        assertThat(historyCount(future)).isEqualTo(1);
        assertThat(eventCount(paid)).isZero();
    }

    @Test
    @DisplayName("키셋은 같은 기한을 아이디 순으로 잇고 다른 상태 · 기한 전은 빼며, 만료한 주문은 다시 읽지 않는다")
    void keysetVisitsEqualDeadlinesInIdOrderAndExcludesOtherStatesAndFuture() {
        Instant due = NOW.minusSeconds(1000000);
        long a = create(due, OrderStatus.PENDING_PAYMENT);
        long b = create(due, OrderStatus.PENDING_PAYMENT);
        long c = create(due.plusSeconds(1), OrderStatus.PENDING_PAYMENT);
        create(due, OrderStatus.PAID);
        create(due.plusSeconds(2), OrderStatus.PENDING_PAYMENT);
        Instant cutoff = due.plusSeconds(1);
        List<OrderExpiryCandidate> first = processor.findDue(cutoff, null, 2);
        assertThat(first).extracting(OrderExpiryCandidate::orderId).containsExactly(a, b);
        assertThat(processor.findDue(cutoff, first.getLast(), 2)).extracting(OrderExpiryCandidate::orderId).containsExactly(c);
        assertThat(List.of(a, b, c)).allSatisfy(id -> assertThat(processor.expire(id, NOW)).isTrue());
        assertThat(processor.findDue(cutoff, null, 10)).isEmpty();
    }

    @Test
    @DisplayName("읽은 뒤 결제 완료가 커밋됐으면 옛 결제 대기 값으로 덮어쓰지 못한다")
    void stalePendingCannotOverwriteActualPaidMutation() {
        long id = create(NOW, OrderStatus.PENDING_PAYMENT);
        Order stale = read(id);
        tx().executeWithoutResult(s -> jdbc.update("update orders.orders set status = 'PAID', version = version + 1 where id = ?", id));
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> orders.saveExpiry(stale.expire(NOW), OrderStatusHistory.expired(IDS.incrementAndGet(), stale))))
            .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(read(id).status()).isEqualTo(OrderStatus.PAID);
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(eventCount(id)).isZero();
    }

    @Test
    @DisplayName("만료가 읽은 직후 결제 완료가 커밋되면 만료는 버전 충돌로 롤백된다")
    void concurrentPaidCommitFencesExpiryAfterItsPendingRead() throws Exception {
        long id = create(NOW, OrderStatus.PENDING_PAYMENT);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch paid = new CountDownLatch(1);
        OrderRepositoryPort target = AopTestUtils.getUltimateTargetObject(orders);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            loaded.countDown();
            if (!paid.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Payment writer did not complete");
            }
            return result;
        }).when(target).findById(id);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> expiry = pool.submit(() -> {
                try {
                    return processor.expire(id, NOW);
                } catch (OptimisticLockingFailureException expected) {
                    return false;
                }
            });
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            tx().executeWithoutResult(status -> assertThat(jdbc.update(
                "update orders.orders set status = 'PAID', version = version + 1 where id = ? and version = 0 and status = 'PENDING_PAYMENT'", id)).isEqualTo(1));
            paid.countDown();
            assertThat(expiry.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            paid.countDown();
            pool.shutdownNow();
            reset(target);
        }
        assertThat(read(id).status()).isEqualTo(OrderStatus.PAID);
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(eventCount(id)).isZero();
    }

    @Test
    @DisplayName("두 인스턴스가 같은 주문을 동시에 만료하면 하나만 커밋해 이력 · 이벤트가 하나씩이다")
    void twoConcurrentExpiriesWriteOneHistoryAndOneEvent() throws Exception {
        long id = create(NOW, OrderStatus.PENDING_PAYMENT);
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
                    return processor.expire(id, NOW);
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
        assertThat(eventCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("outbox 저장이 뒤늦게 실패하면 상태 · 이력 · outbox 가 함께 롤백된다")
    void outboxLateFailureRollsBackStateHistoryAndOutbox() {
        long id = create(NOW, OrderStatus.PENDING_PAYMENT);
        OrderEventAdapter target = AopTestUtils.getUltimateTargetObject(events);
        long reservationId = read(id).stockReservationId();
        doAnswer(call -> {
            call.callRealMethod();
            outbox.flush();
            throw new IllegalStateException("SimulatedFailure");
        }).when(target).expired(id, reservationId);
        try {
            assertThatThrownBy(() -> processor.expire(id, NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(read(id).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(historyCount(id)).isEqualTo(1);
            assertThat(eventCount(id)).isZero();
        } finally {
            reset(target);
        }
    }

    @Test
    @DisplayName("이력 저장이 실패하면 이미 flush 한 상태도 롤백된다")
    void historyFailureRollsBackAlreadyFlushedState() {
        long id = create(NOW, OrderStatus.PENDING_PAYMENT);
        OrderStatusHistoryRepository target = AopTestUtils.getUltimateTargetObject(histories);
        doThrow(new IllegalStateException("SimulatedHistoryFailure")).when(target).save(any());
        try {
            assertThatThrownBy(() -> processor.expire(id, NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(read(id).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(historyCount(id)).isEqualTo(1);
            assertThat(eventCount(id)).isZero();
        } finally {
            reset(target);
        }
    }

    /**
     * Facade 를 실제 Processor · DB 로 돌린다 — 같은 기한 주문 5건을 2건씩 키셋으로 읽으며 2번째 건이 실패해도 뒤 건을 모두 만료하고,
     * 다음 실행이 실패 건만 다시 만료한다. 이미 만료한 건은 다시 읽지 않아 이력 · 이벤트가 주문마다 하나다.
     * 기한을 다른 테스트의 어떤 주문보다 앞에 두고 그 시각으로 돌려, 같은 DB 를 쓰는 다른 테스트의 주문을 건드리지 않는다.
     */
    @Test
    @DisplayName("스윕 — 같은 기한이 배치 경계를 넘어도 빠짐없이 만료하고, 실패 건은 뒤 건을 막지 않으며 다음 실행에서 한 번만 만료된다")
    void sweepCoversEqualDeadlinesAcrossBatchesSkipsFailureAndRetriesItNextRun() {
        Instant due = NOW.minusSeconds(5_000_000);
        List<Long> ids = Stream.generate(() -> create(due, OrderStatus.PENDING_PAYMENT)).limit(5).toList();
        long failing = ids.get(1);
        OrderSchedulerFacade sweep = new OrderSchedulerFacade(processor, new OrderPaymentExpiryProperties(true, Duration.ofSeconds(30), 2, 10),
            Clock.fixed(due, ZoneOffset.UTC));
        OrderRepositoryPort target = AopTestUtils.getUltimateTargetObject(orders);
        doThrow(new IllegalStateException("SimulatedFailure")).when(target).findById(failing);
        try {
            sweep.expireOverdueOrders();
        } finally {
            reset(target);
        }
        assertThat(ids).allSatisfy(id -> assertThat(read(id).status()).isEqualTo(id == failing ? OrderStatus.PENDING_PAYMENT : OrderStatus.EXPIRED));
        assertThat(eventCount(failing)).isZero();

        sweep.expireOverdueOrders();
        sweep.expireOverdueOrders();

        assertThat(ids).allSatisfy(id -> {
            assertThat(read(id).status()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(historyCount(id)).isEqualTo(2);
            assertThat(eventCount(id)).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("outbox 저장소 SPI — 발행 대상 조회 · 실패 · 발행 표시 · 정리가 조건부로 동작한다")
    void outboxSpiQueriesMarksAndPurgesConditionally() {
        Instant marker = NOW.minusSeconds(2000000);
        tx().executeWithoutResult(s -> {
            store.append(new OutboxRow(null, "order", "spi-first", "order-expired", 1, "{}", marker, 0));
            store.append(new OutboxRow(null, "order", "spi-future", "order-expired", 1, "{}", marker.plusSeconds(10), 0));
        });
        var rows = store.findPublishable(marker, 100);
        assertThat(rows).extracting(OutboxRow::aggregateId).containsExactly("spi-first");
        long id = rows.getFirst().id();
        store.markFailed(id, "SimulatedFailure");
        assertThat(outbox.findById(id).orElseThrow().getPublishAttempts()).isEqualTo(1);
        assertThat(store.markPublished(id, marker)).isTrue();
        assertThat(store.markPublished(id, marker.plusSeconds(1))).isFalse();
        store.markFailed(id, "Ignored");
        assertThat(outbox.findById(id).orElseThrow().getPublishAttempts()).isEqualTo(1);
        assertThat(store.deletePublishedBefore(marker, 100)).isZero();
        assertThat(store.deletePublishedBefore(marker.plusSeconds(1), 100)).isEqualTo(1);
        assertThat(outbox.findAll()).anyMatch(e -> e.getAggregateId().equals("spi-future") && e.getPublishedAt() == null);
    }
}
