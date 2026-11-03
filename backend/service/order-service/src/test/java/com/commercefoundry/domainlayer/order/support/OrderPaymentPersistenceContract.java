package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.order.application.exception.OrderPaymentSaveException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentResultInternalUseCase;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment.Result;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 반영 기록({@code orders.order_payment}) · inbox({@code orders.processed_event}) 영속성 계약 (#308 PR-B1) — H2({@code OrderPaymentH2Test}) ·
 * PostgreSQL({@code OrderPaymentPostgresTest})이 같은 단언을 돌린다. 결제 완료 반영(주문 상태 · 선점 아이디 · 이력 · 기록 · inbox)이 한 커밋인지,
 * 결제당 한 행 · 주문당 결제 완료 하나 유니크와 check 를 본다. 유니크 위반의 사유 판정(제약 이름)은 PostgreSQL 만 본다 — H2 는 이름을 싣지 않는다.
 */
public abstract class OrderPaymentPersistenceContract {

    protected static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T00:00:01.123456Z");
    private static final String GROUP = "commerce-foundry.test.order.payment-approved";
    private static final AtomicLong IDS = new AtomicLong(900_000);

    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected OrderPaymentRepositoryPort orderPayments;
    @Autowired protected JpaProcessedEventStore processedEventStore;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected OrderPaymentResultInternalUseCase paymentResults;
    @Autowired protected EntityManagerFactory entityManagerFactory;

    /** 위반한 제약 이름으로 사유를 가르는가 — PostgreSQL 이면 true, H2 는 이름이 없어 {@code REJECTED} 다. */
    protected abstract boolean reportsConstraintNames();

    @ParameterizedTest
    @EnumSource(Result.class)
    @DisplayName("최초 결제 반영 결과는 주문 행 없이 한 SELECT 로 읽고 저장된 전체 행을 바꾸지 않는다")
    void persistedResultIsIndependentOfCurrentOrderAndReadOnly(Result result) {
        long orderId = IDS.incrementAndGet();
        long paymentId = IDS.incrementAndGet();
        Long reservationId = result.recordsConfirmedReservation() ? IDS.incrementAndGet() : null;
        Instant approvedAt = result == Result.REJECTED_PAYMENT_NOT_DONE ? null : APPROVED_AT;
        OrderPayment source = new OrderPayment(IDS.incrementAndGet(), orderId, paymentId, 7L, krw(1000), approvedAt,
            result, reservationId, UUID.randomUUID(), null, null, null);
        tx().executeWithoutResult(status -> orderPayments.insert(source));
        var before = jdbc.queryForMap("select * from orders.order_payment where payment_id = ?", paymentId);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var response = paymentResults.getResult(paymentId);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityDeleteCount()).isZero();
        assertThat(response.paymentId()).isEqualTo(String.valueOf(paymentId));
        assertThat(response.orderId()).isEqualTo(String.valueOf(orderId));
        assertThat(response.memberId()).isEqualTo("7");
        assertThat(response.result()).isEqualTo(result.name());
        assertThat(response.amount().amount()).isEqualTo(1000);
        assertThat(response.amount().currency()).isEqualTo("KRW");
        assertThat(response.approvedAt()).isEqualTo(approvedAt);
        assertThat(response.stockReservationId()).isEqualTo(reservationId == null ? null : String.valueOf(reservationId));
        assertThat(jdbc.queryForMap("select * from orders.order_payment where payment_id = ?", paymentId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from orders.orders where id = ?", Long.class, orderId)).isZero();
    }

    @Test
    @DisplayName("USD 센트와 int 범위 초과 금액은 저장 · 결과 조회에서 환산이나 축소 없이 보존한다")
    void usdMinorUnitsAboveIntegerRangeRoundTrip() {
        long paymentId = IDS.incrementAndGet();
        OrderPayment source = new OrderPayment(IDS.incrementAndGet(), IDS.incrementAndGet(), paymentId, 7L,
            Money.of(4_294_967_295L, CurrencyCode.USD), APPROVED_AT, Result.PAID, IDS.incrementAndGet(), UUID.randomUUID(), null, null, null);
        tx().executeWithoutResult(status -> orderPayments.insert(source));
        var before = jdbc.queryForMap("select * from orders.order_payment where payment_id = ?", paymentId);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var response = paymentResults.getResult(paymentId);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(response.paymentId()).isEqualTo(String.valueOf(paymentId));
        assertThat(response.result()).isEqualTo("PAID");
        assertThat(response.amount().amount()).isEqualTo(4_294_967_295L);
        assertThat(response.amount().currency()).isEqualTo("USD");
        assertThat(response.approvedAt()).isEqualTo(APPROVED_AT);
        assertThat(response.stockReservationId()).isEqualTo(String.valueOf(source.stockReservationId()));
        assertThat(jdbc.queryForMap("select * from orders.order_payment where payment_id = ?", paymentId)).isEqualTo(before);
    }

    @Test
    @DisplayName("최신 주문 상태가 바뀌어도 결제 반영 결과는 최초 PAID 그대로다")
    void currentOrderStateDoesNotReinterpretPaidResult() {
        long orderId = place(OrderStatus.PENDING_PAYMENT);
        long paymentId = IDS.incrementAndGet();
        Order paid = tx().execute(status -> orders.findById(orderId).orElseThrow()).markPaid(IDS.incrementAndGet());
        tx().executeWithoutResult(status -> orderPayments.insert(
            OrderPayment.paid(IDS.incrementAndGet(), paid, paymentId, krw(1000), APPROVED_AT, UUID.randomUUID())));
        // 후속 흐름이 진행된 주문을 구성한다. 조회 경로는 현재 주문 상태를 해석하지 않아야 한다.
        jdbc.update("update orders.orders set status = 'PREPARING', version = version + 1 where id = ?", orderId);

        var response = paymentResults.getResult(paymentId);

        assertThat(response.result()).isEqualTo("PAID");
        assertThat(response.approvedAt()).isEqualTo(APPROVED_AT);
        assertThat(response.stockReservationId()).isEqualTo(String.valueOf(paid.stockReservationId()));
    }

    @Test
    @DisplayName("결제 완료 반영 — 주문 PAID · 새 선점 아이디 · SERVICE/PAYMENT_APPROVED 이력 · 결제 기록 · inbox 가 한 커밋에 남고, 결제 · 주문으로 다시 읽힌다")
    void paidWritesOrderHistoryRecordAndInboxTogether() {
        long orderId = place(OrderStatus.EXPIRED);
        long paymentId = IDS.incrementAndGet();
        long newReservationId = IDS.incrementAndGet();
        UUID eventId = UUID.randomUUID();

        OrderPayment saved = tx().execute(status -> {
            Order before = orders.findById(orderId).orElseThrow();
            Order paid = before.markPaid(newReservationId);
            orders.saveStatusChange(paid, OrderStatusHistory.changedByService(IDS.incrementAndGet(), before, paid, OrderStatusHistory.PAYMENT_APPROVED));
            processedEventStore.record(GROUP, eventId, "payment-approved", NOW);
            return orderPayments.insert(OrderPayment.paid(IDS.incrementAndGet(), paid, paymentId, krw(1000), APPROVED_AT, eventId));
        });

        assertThat(saved.createdAt()).isNotNull();
        Order read = tx().execute(status -> orders.findById(orderId).orElseThrow());
        assertThat(read.status()).isEqualTo(OrderStatus.PAID);
        assertThat(read.stockReservationId()).isEqualTo(newReservationId);
        assertThat(jdbc.queryForObject("select reason from orders.order_status_history where order_id = ? and to_status = 'PAID'", String.class, orderId))
            .isEqualTo("PAYMENT_APPROVED");
        assertThat(processedEventStore.exists(GROUP, eventId)).isTrue();
        OrderPayment byPayment = orderPayments.findByPaymentId(paymentId).orElseThrow();
        assertThat(byPayment).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(saved);
        assertThat(byPayment.approvedAt()).isEqualTo(APPROVED_AT);
        assertThat(byPayment.eventId()).isEqualTo(eventId);
        assertThat(orderPayments.findPaidByOrderId(orderId)).contains(byPayment);
        assertThat(jdbc.queryForObject("select paid_order_id from orders.order_payment where payment_id = ?", Long.class, paymentId)).isEqualTo(orderId);
    }

    @Test
    @DisplayName("기록이 실패하면(같은 결제 다시) 주문 상태 · 이력 · inbox 도 함께 롤백된다")
    void duplicatePaymentRollsBackWholeReflection() {
        long orderId = place(OrderStatus.PENDING_PAYMENT);
        long paymentId = IDS.incrementAndGet();
        tx().executeWithoutResult(status -> orderPayments.insert(rejected(orderId, paymentId, Result.REJECTED_PAYMENT_MISMATCH)));
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            Order before = orders.findById(orderId).orElseThrow();
            Order paid = before.markPaid(before.stockReservationId());
            orders.saveStatusChange(paid, OrderStatusHistory.changedByService(IDS.incrementAndGet(), before, paid, OrderStatusHistory.PAYMENT_APPROVED));
            processedEventStore.record(GROUP, eventId, "payment-approved", NOW);
            orderPayments.insert(OrderPayment.paid(IDS.incrementAndGet(), paid, paymentId, krw(1000), APPROVED_AT, eventId));
        })).isInstanceOfSatisfying(OrderPaymentSaveException.class, exception -> {
            if (reportsConstraintNames()) {
                assertThat(exception.getReason()).isEqualTo(OrderPaymentSaveException.Reason.PAYMENT_ALREADY_RECORDED);
            }
            assertThat(exception.getCause()).isNull();
        });

        assertThat(tx().execute(status -> orders.findById(orderId).orElseThrow()).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id = ?", Long.class, orderId)).isEqualTo(1);
        assertThat(processedEventStore.exists(GROUP, eventId)).isFalse();
    }

    @Test
    @DisplayName("주문당 결제 완료는 하나다 — 다른 결제의 두 번째 PAID 는 ORDER_ALREADY_PAID, 거절 기록은 같은 주문에 여럿 둘 수 있다")
    void onePaidPerOrder() {
        long orderId = place(OrderStatus.PENDING_PAYMENT);
        Order paid = tx().execute(status -> orders.findById(orderId).orElseThrow()).markPaid(IDS.incrementAndGet());
        tx().executeWithoutResult(status -> orderPayments.insert(
            OrderPayment.paid(IDS.incrementAndGet(), paid, IDS.incrementAndGet(), krw(1000), APPROVED_AT, UUID.randomUUID())));
        tx().executeWithoutResult(status -> {
            orderPayments.insert(rejected(orderId, IDS.incrementAndGet(), Result.REJECTED_ALREADY_PAID));
            orderPayments.insert(rejected(orderId, IDS.incrementAndGet(), Result.REJECTED_ALREADY_PAID));
        });

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> orderPayments.insert(
            OrderPayment.paid(IDS.incrementAndGet(), paid, IDS.incrementAndGet(), krw(1000), APPROVED_AT, UUID.randomUUID()))))
            .isInstanceOfSatisfying(OrderPaymentSaveException.class, exception -> {
                if (reportsConstraintNames()) {
                    assertThat(exception.getReason()).isEqualTo(OrderPaymentSaveException.Reason.ORDER_ALREADY_PAID);
                }
            });
        assertThat(jdbc.queryForObject("select count(*) from orders.order_payment where order_id = ?", Long.class, orderId)).isEqualTo(3);
    }

    @Test
    @DisplayName("check — 결제 완료가 아닌데 paid_order_id 가 있거나, PAID 인데 다른 주문 · 확정 선점 없음 · 복원 클레임, 금액 0 은 DB 가 거절한다")
    void checkConstraints() {
        String insert = "insert into orders.order_payment (id, created_at, updated_at, order_id, payment_id, member_id, total_amount, total_currency,"
            + " approved_at, result, paid_order_id, stock_reservation_id, event_id, restoration_claim_id, restored_at)"
            + " values (?, current_timestamp, current_timestamp, 1, ?, 7, ?, 'KRW', current_timestamp, ?, ?, ?, ?, ?, null)";
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "REJECTED_ORDER_CANCELED", 1L, null, UUID.randomUUID(), null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "PAID", 2L, 5L, UUID.randomUUID(), null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "PAID", 1L, null, UUID.randomUUID(), null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "PAID", 1L, 5L, UUID.randomUUID(), 9L))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 0, "REJECTED_ORDER_NOT_FOUND", null, null, UUID.randomUUID(), null))
            .isInstanceOf(DataIntegrityViolationException.class);
        // 이중 결제에서 진 쪽(ALREADY_PAID) · 그 밖의 거절은 확정 선점 · 복원 클레임을 가질 수 없다 — PR-C 가 이긴 주문의 재고를 되돌리지 않게
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "REJECTED_ALREADY_PAID", null, 5L, UUID.randomUUID(), null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "REJECTED_ALREADY_PAID", null, 5L, UUID.randomUUID(), 9L))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "REJECTED_STOCK_UNAVAILABLE", null, 5L, UUID.randomUUID(), null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "REJECTED_ORDER_CANCELED", null, null, UUID.randomUUID(), 9L))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "REJECTED_ORDER_CANCELED", null, 5L, UUID.randomUUID(), 9L)).isEqualTo(1);
        assertThat(jdbc.update(insert, IDS.incrementAndGet(), IDS.incrementAndGet(), 1000, "REJECTED_PAYMENT_NOT_DONE", null, null, UUID.randomUUID(), null)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 트랜잭션에 먼저 넣은 inbox 기록이 중복이면 insert 의 첫 flush 가 가른다 — PostgreSQL 은 EVENT_ALREADY_PROCESSED, 기록은 남지 않는다")
    void inboxDuplicateBeforeInsertIsSeparated() {
        UUID eventId = UUID.randomUUID();
        tx().executeWithoutResult(status -> processedEventStore.record(GROUP, eventId, "payment-approved", NOW));
        long paymentId = IDS.incrementAndGet();

        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> {
            processedEventStore.record(GROUP, eventId, "payment-approved", NOW);
            orderPayments.insert(rejected(1L, paymentId, Result.REJECTED_ORDER_NOT_FOUND));
        }));

        if (reportsConstraintNames()) {
            assertThat(thrown).isInstanceOfSatisfying(OrderPaymentSaveException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(OrderPaymentSaveException.Reason.EVENT_ALREADY_PROCESSED));
        } else {
            // H2 는 제약 이름이 없어 앞선 쓰기 실패를 원래 예외 그대로 던진다 — 기록 실패(OrderPaymentSaveException)로 섞이지 않는다
            assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(orderPayments.findByPaymentId(paymentId)).isEmpty();
    }

    @Test
    @DisplayName("기록 · inbox 기록은 업무 트랜잭션 안에서만 — 밖에서 부르면 실패하고 남지 않는다")
    void writesRequireTransaction() {
        long paymentId = IDS.incrementAndGet();
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> orderPayments.insert(rejected(1L, paymentId, Result.REJECTED_ORDER_NOT_FOUND))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> processedEventStore.record(GROUP, eventId, "payment-approved", NOW)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(orderPayments.findByPaymentId(paymentId)).isEmpty();
        assertThat(processedEventStore.exists(GROUP, eventId)).isFalse();
    }

    @Test
    @DisplayName("inbox — 같은 그룹 · 이벤트를 두 번 기록하면 커밋이 실패하고, 보존 기간 정리는 오래된 것만 지운다")
    void inboxUniqueAndCleanup() {
        UUID eventId = UUID.randomUUID();
        tx().executeWithoutResult(status -> processedEventStore.record(GROUP, eventId, "payment-approved", NOW.minusSeconds(3600)));

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> processedEventStore.record(GROUP, eventId, "payment-approved", NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(processedEventStore.exists(GROUP + "-other", eventId)).isFalse();
        UUID recent = UUID.randomUUID();
        tx().executeWithoutResult(status -> processedEventStore.record(GROUP, recent, "payment-approved", NOW));

        assertThat(processedEventStore.deleteProcessedBefore(NOW.minusSeconds(60), 100)).isGreaterThanOrEqualTo(1);
        assertThat(processedEventStore.exists(GROUP, eventId)).isFalse();
        assertThat(processedEventStore.exists(GROUP, recent)).isTrue();
    }

    private static OrderPayment rejected(long orderId, long paymentId, Result result) {
        return OrderPayment.rejected(IDS.incrementAndGet(), orderId, paymentId, 7L, krw(1000), APPROVED_AT, result, UUID.randomUUID());
    }

    private long place(OrderStatus status) {
        long id = IDS.incrementAndGet();
        Order order = Order.place(id, 7L, domesticQuote(quoteLine(101, 1000, 1)), IDS::incrementAndGet, address("KR"), IDS.incrementAndGet(),
            NOW.plusSeconds(1800));
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            if (status != OrderStatus.PENDING_PAYMENT) {
                // 만료는 이 계약의 대상이 아니다 — 상태만 직접 맞춘다(버전도 올린다)
                jdbc.update("update orders.orders set status = ?, version = version + 1 where id = ?", status.name(), id);
            }
        });
        return id;
    }

    @Test
    @DisplayName("markRestored — 복원 클레임이 있고 복원 시각이 빈 행만 한 번 채우고 updated_at 도 바꾼다. 클레임 없는 행 · 두 번째 호출은 false 다 (#308 PR-C1)")
    void markRestoredIsConditional() {
        Instant restoredAt = NOW.plusSeconds(60);
        OrderPayment restorable = tx().execute(status -> orderPayments.insert(OrderPayment.canceledAfterConfirmation(IDS.incrementAndGet(), IDS.incrementAndGet(),
            IDS.incrementAndGet(), 7L, krw(1000), APPROVED_AT, IDS.incrementAndGet(), UUID.randomUUID(), IDS.incrementAndGet())));
        OrderPayment withoutClaim = tx().execute(status -> orderPayments.insert(OrderPayment.rejected(IDS.incrementAndGet(), IDS.incrementAndGet(),
            IDS.incrementAndGet(), 7L, krw(1000), APPROVED_AT, Result.REJECTED_ORDER_CANCELED, UUID.randomUUID())));

        Boolean first = tx().execute(status -> orderPayments.markRestored(restorable.id(), restoredAt));
        Boolean second = tx().execute(status -> orderPayments.markRestored(restorable.id(), restoredAt.plusSeconds(60)));
        Boolean noClaim = tx().execute(status -> orderPayments.markRestored(withoutClaim.id(), restoredAt));
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(noClaim).isFalse();

        assertThat(orderPayments.findByPaymentId(restorable.paymentId()).orElseThrow().restoredAt()).isEqualTo(restoredAt);
        assertThat(jdbc.queryForObject("select updated_at from orders.order_payment where id = ?", java.sql.Timestamp.class, restorable.id()).toInstant())
            .isEqualTo(restoredAt);
        assertThat(orderPayments.findByPaymentId(withoutClaim.paymentId()).orElseThrow().restoredAt()).isNull();
        assertThatThrownBy(() -> orderPayments.markRestored(restorable.id(), restoredAt)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
