package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.out.event.OrderEventAdapter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.OrderMemberSummaryInfo;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderHistoryProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.global.messaging.OutboxRepository;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 결제 전 고객 취소 · 주문 내역 영속성 계약 (#16) — H2({@code OrderCancelH2Test}) · PostgreSQL({@code OrderCancelPostgresTest})이 같은 단언을 돌린다.
 * 상태 · 이력 · {@code order-canceled} outbox 가 한 트랜잭션인지, 봉투 · payload 가 {@code order-flow-guide.md} §3-5 정본과 같은지, 버전 충돌 · 남의 주문 · 내역 조각을 본다.
 */
public abstract class OrderCancelPersistenceContract {

    protected static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final Path CONTRACT_DOCUMENT = Path.of("..", "..", "docs", "order-flow-guide.md");
    private static final String CONTRACT_SECTION = "### 3-5.";
    private static final AtomicLong IDS = new AtomicLong(700000);

    @MockitoSpyBean protected OrderRepositoryPort orders;
    @MockitoSpyBean protected OrderEventAdapter events;
    @Autowired protected OrderHistoryProcessor processor;
    @Autowired protected OutboxRepository outbox;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("결제 대기 주문을 취소하면 상태 · 고객 이력(사유 코드) · order-canceled 가 함께 남고 봉투 · payload 가 §3-5 정본과 같다 — 다시 취소해도 더 쓰지 않는다")
    void cancelWritesStatusHistoryAndContractEventOnce() throws IOException {
        long member = IDS.incrementAndGet();
        long id = place(member, OrderStatus.PENDING_PAYMENT, quoteLine(101, 1000, 1));
        Order before = read(id);

        assertThat(processor.cancel(member, id, OrderCancelReason.CHANGE_OF_MIND).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(processor.cancel(member, id, OrderCancelReason.OTHER).status()).isEqualTo(OrderStatus.CANCELED);

        assertThat(read(id).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(jdbc.queryForList("select reason from orders.order_status_history where order_id = ? and to_status = 'CANCELED'", String.class, id))
            .containsExactly("CHANGE_OF_MIND");
        assertThat(historyCount(id)).isEqualTo(2);
        assertThat(eventCount(id)).isEqualTo(1);

        var row = outbox.findAll().stream().filter(e -> e.getAggregateId().equals(Long.toString(id))).findFirst().orElseThrow();
        EventEnvelope envelope = new EventEnvelopeCodec(65536).decode(row.getPayload());
        assertThat(envelope.eventType()).isEqualTo("order-canceled");
        assertThat(envelope.aggregateId()).isEqualTo(Long.toString(id));
        assertThat(envelope.payload().path("orderId").asString()).isEqualTo(Long.toString(id));
        assertThat(envelope.payload().path("reservationId").asString()).isEqualTo(Long.toString(before.stockReservationId()));
        assertThat(envelope.payload().path("reason").asString()).isEqualTo("CHANGE_OF_MIND");
        assertMatchesContractDocument(envelope);
    }

    @Test
    @DisplayName("남의 주문은 없는 주문(ORDER_001), 결제 완료 · 만료는 ORDER_020 이고 아무것도 쓰지 않는다")
    void rejectsOthersAndNonPendingWithoutWriting() {
        long member = IDS.incrementAndGet();
        long pending = place(member, OrderStatus.PENDING_PAYMENT, quoteLine(101, 1000, 1));
        long paid = place(member, OrderStatus.PAID, quoteLine(101, 1000, 1));
        long expired = place(member, OrderStatus.EXPIRED, quoteLine(101, 1000, 1));

        assertRejected(member + 1, pending, OrderErrorCode.NOT_FOUND_ORDER);
        assertRejected(member, paid, OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        assertRejected(member, expired, OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);

        assertThat(read(pending).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(List.of(pending, paid, expired)).allSatisfy(id -> {
            assertThat(historyCount(id)).isEqualTo(1);
            assertThat(eventCount(id)).isZero();
        });
    }

    @Test
    @DisplayName("outbox 저장이 실패하면 취소 전이 · 이력도 함께 롤백된다")
    void outboxFailureRollsBackCancel() {
        long member = IDS.incrementAndGet();
        long id = place(member, OrderStatus.PENDING_PAYMENT, quoteLine(101, 1000, 1));
        doThrow(new IllegalStateException("outbox down")).when(events).canceled(eq(id), anyLong(), any());
        try {
            assertThatThrownBy(() -> processor.cancel(member, id, OrderCancelReason.CHANGE_OF_MIND)).isInstanceOf(IllegalStateException.class);
        } finally {
            reset(events);
        }
        assertThat(read(id).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(eventCount(id)).isZero();
    }

    @Test
    @DisplayName("취소가 읽은 뒤 만료(또는 결제 완료)가 먼저 커밋되면 취소는 버전 충돌로 롤백되고 이벤트도 남지 않는다 — 다시 부르면 ORDER_020 이다")
    void staleCancelLosesToCommittedTransition() {
        long member = IDS.incrementAndGet();
        long id = place(member, OrderStatus.PENDING_PAYMENT, quoteLine(101, 1000, 1));
        Order stale = read(id);
        tx().executeWithoutResult(s -> assertThat(jdbc.update(
            "update orders.orders set status = 'EXPIRED', version = version + 1 where id = ? and version = ? and status = 'PENDING_PAYMENT'", id, stale.version())).isEqualTo(1));

        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            orders.saveCustomerCancel(stale.cancelBeforePayment(), OrderStatusHistory.canceledByCustomer(IDS.incrementAndGet(), stale, OrderCancelReason.CHANGE_OF_MIND));
            events.canceled(stale.id(), stale.stockReservationId(), OrderCancelReason.CHANGE_OF_MIND);
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(read(id).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(eventCount(id)).isZero();
        assertRejected(member, id, OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("취소 트랜잭션이 주문을 읽은 직후 만료가 커밋되면 flush 의 버전 비교로 취소가 롤백된다 — 이력 · 이벤트가 남지 않는다")
    void expiryCommittedAfterCancelReadFencesCancel() throws Exception {
        long member = IDS.incrementAndGet();
        long id = place(member, OrderStatus.PENDING_PAYMENT, quoteLine(101, 1000, 1));
        long version = read(id).version();
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch expired = new CountDownLatch(1);
        OrderRepositoryPort target = AopTestUtils.getUltimateTargetObject(orders);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            loaded.countDown();
            if (!expired.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Expiry did not complete");
            }
            return result;
        }).when(target).findByIdAndMemberId(id, member);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Order> cancel = pool.submit(() -> processor.cancel(member, id, OrderCancelReason.CHANGE_OF_MIND));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            // 만료 스윕의 커밋 — 같은 행을 버전 비교로 바꾼다
            tx().executeWithoutResult(s -> assertThat(jdbc.update(
                "update orders.orders set status = 'EXPIRED', version = version + 1 where id = ? and version = ? and status = 'PENDING_PAYMENT'", id, version)).isEqualTo(1));
            expired.countDown();
            assertThatThrownBy(() -> cancel.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            expired.countDown();
            pool.shutdownNow();
            reset(target);
        }
        assertThat(read(id).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(eventCount(id)).isZero();
    }

    @Test
    @DisplayName("내역 — 내 주문만 최신순 조각으로, 다음 조각이 있는지와 주문 이름 재료(첫 상품 · 항목 수)를 함께 읽는다")
    void memberSliceIsLatestFirstAndOwnOnly() {
        long member = IDS.incrementAndGet();
        long first = place(member, OrderStatus.PAID, quoteLine(101, 1000, 1), quoteLine(102, 2000, 2));
        long second = place(member, OrderStatus.PENDING_PAYMENT, quoteLine(103, 3000, 1));
        long third = place(member, OrderStatus.PENDING_PAYMENT, quoteLine(104, 4000, 1));
        place(member + 1, OrderStatus.PENDING_PAYMENT, quoteLine(105, 5000, 1));

        Slice<OrderMemberSummaryInfo> head = tx().execute(s -> processor.findSlice(member, 0, 2));
        assertThat(head.getContent()).extracting(OrderMemberSummaryInfo::orderId).containsExactly(third, second);
        assertThat(head.hasNext()).isTrue();

        Slice<OrderMemberSummaryInfo> tail = tx().execute(s -> processor.findSlice(member, 1, 2));
        assertThat(tail.getContent()).extracting(OrderMemberSummaryInfo::orderId).containsExactly(first);
        assertThat(tail.hasNext()).isFalse();
        OrderMemberSummaryInfo row = tail.getContent().getFirst();
        assertThat(row.firstProductName()).isEqualTo("상품101");
        assertThat(row.itemCount()).isEqualTo(2);
        assertThat(row.totalAmount()).isPositive();
        assertThat(row.cancelable()).isFalse();

        Slice<OrderMemberSummaryInfo> beyond = tx().execute(s -> processor.findSlice(member, 5, 2));
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    private void assertRejected(long memberId, long orderId, OrderErrorCode code) {
        assertThatThrownBy(() -> processor.cancel(memberId, orderId, OrderCancelReason.CHANGE_OF_MIND))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private long place(long memberId, OrderStatus status, OrderQuoteLine... lines) {
        long id = IDS.incrementAndGet();
        var order = Order.place(id, memberId, domesticQuote(lines), IDS::incrementAndGet, address("KR"), IDS.incrementAndGet(), Instant.now().plusSeconds(1800));
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            if (status != OrderStatus.PENDING_PAYMENT) {
                // 결제 완료(#14) · 만료는 이 계약의 대상이 아니다 — 상태만 직접 맞춘다(버전도 올린다)
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

    /**
     * {@code order-flow-guide.md} §3-5 가 이벤트 계약 정본이다 — 봉투 행에 이 eventType 이 있고, payload 필드 · 타입이 json 블록과 같고,
     * {@code reason} 이 사유 행에 적힌 코드여야 한다(product 리스너 테스트가 같은 블록을 읽는다).
     */
    private static void assertMatchesContractDocument(EventEnvelope envelope) throws IOException {
        List<String> lines = Files.readAllLines(CONTRACT_DOCUMENT, StandardCharsets.UTF_8);
        int section = indexOf(lines, CONTRACT_SECTION, 0);
        assertThat(lines.get(indexOf(lines, "| 봉투 |", section))).contains(
            "`" + envelope.eventType() + "`",
            "`aggregateType` = `" + envelope.aggregateType() + "`",
            "`schemaVersion` = " + envelope.schemaVersion(),
            "`producer` = `" + envelope.producer() + "`"
        );
        assertThat(lines.get(indexOf(lines, "| `reason` 코드 |", section))).contains("`" + envelope.payload().path("reason").asString() + "`");
        int start = indexOf(lines, "```json", section) + 1;
        JsonNode contract = new ObjectMapper().readTree(String.join("\n", lines.subList(start, indexOf(lines, "```", start))));
        JsonNode actual = envelope.payload();
        assertThat(fieldNames(actual)).isEqualTo(fieldNames(contract));
        assertThat(fieldNames(contract)).allSatisfy(name -> assertThat(actual.get(name).getNodeType()).isEqualTo(contract.get(name).getNodeType()));
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
        throw new AssertionError(CONTRACT_DOCUMENT.toAbsolutePath() + " 에서 [" + prefix + "] 를 찾지 못했다 — 계약 절(§3-5)의 봉투 · reason 코드 행과 json 블록이 있어야 한다");
    }
}
