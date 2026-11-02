package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.model.OrderStatusFilter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderFieldValidationException;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.info.OrderAdminSummaryInfo;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderStatusHistoryQueryResult;
import com.commercefoundry.domainlayer.order.application.info.OrderFulfillmentInfo;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAdminProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderFulfillmentProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 어드민 주문 관리 영속성 계약 (#17) — H2({@code OrderAdminH2Test}) · PostgreSQL({@code OrderAdminPostgresTest})이 같은 단언을 돌린다.
 * 어드민 전이 정책 · 이력, 고객 취소와 어드민 처리가 겹칠 때 하나만 성공하는지(@Version), 목록 조회를 본다. 같은 주문 헤더 전이라
 * delivery-service 의 출고 · 배송 완료 반영(#18 — 시각 칸 · SERVICE 이력 · 멱등 · 버전 충돌)도 여기서 본다.
 */
public abstract class OrderAdminPersistenceContract {

    private static final AtomicLong IDS = new AtomicLong(900000);
    private static final long ADMIN_ID = 77;

    @MockitoSpyBean protected OrderRepositoryPort orders;
    @Autowired protected OrderAdminProcessor processor;
    @Autowired protected OrderFulfillmentProcessor fulfillmentProcessor;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("결제 완료 → 상품 준비중으로 바꾸면 상태 · 관리자 이력(아이디 · 사유)이 함께 남고, 같은 요청을 다시 보내면 바꾸지 않는다")
    void changesWithAdminHistoryAndIsIdempotent() {
        long id = create(OrderStatus.PAID, false);

        assertThat(processor.changeStatus(id, new OrderStatusChangeCommand(OrderStatus.PREPARING, " 포장 시작 "), ADMIN_ID)).isTrue();
        assertThat(processor.changeStatus(id, new OrderStatusChangeCommand(OrderStatus.PREPARING, null), ADMIN_ID)).isFalse();

        assertThat(read(id).status()).isEqualTo(OrderStatus.PREPARING);
        List<OrderStatusHistoryQueryResult> histories = tx().execute(status -> orders.findHistories(id));
        assertThat(histories).extracting(OrderStatusHistoryQueryResult::toStatus)
            .containsExactly(OrderStatus.PENDING_PAYMENT, OrderStatus.PREPARING);
        OrderStatusHistoryQueryResult adminRow = histories.getLast();
        assertThat(adminRow.fromStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(adminRow.changedByType()).isEqualTo(StatusChangedBy.ADMIN);
        assertThat(adminRow.changedById()).isEqualTo(ADMIN_ID);
        assertThat(adminRow.reason()).isEqualTo("포장 시작");
        assertThat(adminRow.changedAt()).isNotNull();
    }

    @Test
    @DisplayName("허용되지 않은 전이는 어드민이어도 거절하고 아무것도 쓰지 않는다 — 표 밖 ORDER_020, 다른 흐름의 전이 ORDER_023, 맞춤 제작 없는 제작 중 ORDER_024")
    void rejectsDisallowedTransitionsWithoutWriting() {
        long pending = create(OrderStatus.PENDING_PAYMENT, false);
        long paid = create(OrderStatus.PAID, false);

        assertRejected(pending, OrderStatus.PAID, OrderErrorCode.ADMIN_STATUS_CHANGE_NOT_ALLOWED);
        assertRejected(pending, OrderStatus.EXPIRED, OrderErrorCode.ADMIN_STATUS_CHANGE_NOT_ALLOWED);
        assertRejected(pending, OrderStatus.PREPARING, OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        assertRejected(paid, OrderStatus.SHIPPING, OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        assertRejected(paid, OrderStatus.CANCEL_REQUESTED, OrderErrorCode.ADMIN_STATUS_CHANGE_NOT_ALLOWED);
        assertRejected(paid, OrderStatus.DELIVERED, OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        assertRejected(paid, OrderStatus.MAKING, OrderErrorCode.MAKING_REQUIRES_CUSTOM_ORDER_ITEM);
        assertRejected(Long.MAX_VALUE, OrderStatus.PREPARING, OrderErrorCode.NOT_FOUND_ORDER);

        assertThat(read(pending).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(read(paid).status()).isEqualTo(OrderStatus.PAID);
        assertThat(historyCount(paid)).isEqualTo(1);
    }

    @Test
    @DisplayName("배송중 → 배송 완료는 사유(배송을 확인한 근거)가 있어야 한다 — 없으면 ORDER_027 이고 아무것도 쓰지 않는다")
    void deliveredRequiresReason() {
        long id = create(OrderStatus.PREPARING, false);
        Instant shippedAt = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        fulfillmentProcessor.ship(id, shippedAt);

        assertRejected(id, OrderStatus.DELIVERED, OrderErrorCode.DELIVERED_REASON_REQUIRED);
        assertThat(read(id).status()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(historyCount(id)).isEqualTo(2);

        Instant before = Instant.now().minusSeconds(1);
        assertThat(processor.changeStatus(id, new OrderStatusChangeCommand(OrderStatus.DELIVERED, "택배사 조회로 수령 확인"), ADMIN_ID)).isTrue();
        Order delivered = read(id);
        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered.deliveredAt()).as("수동 완료도 자동 구매 확정 기준 시각을 남긴다 (#18)").isAfter(before);
        assertThat(delivered.shippedAt()).as("수동 완료는 출고 시각을 지킨다").isEqualTo(shippedAt);
        assertThat(historyCount(id)).isEqualTo(3);
    }

    @Test
    @DisplayName("출고 반영 — 상품 준비중이 배송 중이 되고 출고 시각 · SERVICE 이력(SHIPMENT_REGISTERED)이 함께 남는다. 다시 보내면 아무것도 쓰지 않고 지금 상태다")
    void shipsWithServiceHistoryAndIsIdempotent() {
        long id = create(OrderStatus.PREPARING, false);
        Instant shippedAt = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_000);

        OrderFulfillmentInfo shipped = fulfillmentProcessor.ship(id, shippedAt);

        assertThat(shipped.changed()).isTrue();
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(shipped.countryCode()).isEqualTo("KR");
        Order stored = read(id);
        assertThat(stored.status()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(stored.shippedAt()).isEqualTo(shippedAt);
        assertThat(stored.deliveredAt()).isNull();
        OrderStatusHistoryQueryResult serviceRow = tx().execute(status -> orders.findHistories(id)).getLast();
        assertThat(serviceRow.fromStatus()).isEqualTo(OrderStatus.PREPARING);
        assertThat(serviceRow.toStatus()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(serviceRow.changedByType()).isEqualTo(StatusChangedBy.SERVICE);
        assertThat(serviceRow.changedById()).isNull();
        assertThat(serviceRow.reason()).isEqualTo(OrderStatusHistory.SHIPMENT_REGISTERED);

        clearInvocations(orders);
        // 멱등 판정이 시각 범위보다 먼저다 — 범위 밖(40일 전) 시각의 재시도도 이미 반영된 주문이면 200 이다
        OrderFulfillmentInfo again = fulfillmentProcessor.ship(id, Instant.now().minus(40, ChronoUnit.DAYS));
        assertThat(again.changed()).isFalse();
        assertThat(again.status()).isEqualTo(OrderStatus.SHIPPING);
        verify(orders, never()).saveStatusChange(any(), any());
        assertThat(read(id).shippedAt()).as("처음 출고 시각을 덮지 않는다").isEqualTo(shippedAt);
        assertThat(historyCount(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("배송 완료 반영 — 배송 중이 배송 완료가 되고 배송 완료 시각 · SERVICE 이력(DELIVERY_COMPLETED)이 남는다. 출고 시각은 그대로다. 다시 보내면 쓰지 않는다")
    void completesDeliveryWithServiceHistory() {
        long id = create(OrderStatus.PREPARING, false);
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant shippedAt = base.minus(2, ChronoUnit.DAYS);
        Instant deliveredAt = base.minus(1, ChronoUnit.HOURS).plusNanos(654_321_000);
        fulfillmentProcessor.ship(id, shippedAt);

        OrderFulfillmentInfo delivered = fulfillmentProcessor.completeDelivery(id, deliveredAt);

        assertThat(delivered.changed()).isTrue();
        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
        Order stored = read(id);
        assertThat(stored.shippedAt()).isEqualTo(shippedAt);
        assertThat(stored.deliveredAt()).isEqualTo(deliveredAt);
        OrderStatusHistoryQueryResult serviceRow = tx().execute(status -> orders.findHistories(id)).getLast();
        assertThat(serviceRow.changedByType()).isEqualTo(StatusChangedBy.SERVICE);
        assertThat(serviceRow.reason()).isEqualTo(OrderStatusHistory.DELIVERY_COMPLETED);

        assertThat(fulfillmentProcessor.completeDelivery(id, Instant.now().plus(1, ChronoUnit.DAYS)).changed()).as("멱등이 시각 범위보다 먼저다").isFalse();
        assertThat(read(id).deliveredAt()).isEqualTo(deliveredAt);
        assertThat(historyCount(id)).isEqualTo(3);
    }

    @Test
    @DisplayName("반영할 수 없는 상태는 ORDER_020(메시지에 현재 상태), 없는 주문은 ORDER_001 이고 아무것도 쓰지 않는다")
    void rejectsFulfillmentWithoutWriting() {
        long paid = create(OrderStatus.PAID, false);
        long preparing = create(OrderStatus.PREPARING, false);

        assertThatThrownBy(() -> fulfillmentProcessor.ship(paid, Instant.now()))
            .isInstanceOfSatisfying(OrderException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
                assertThat(exception.getMessage()).contains(OrderStatus.PAID.getDisplayName());
            });
        assertThatThrownBy(() -> fulfillmentProcessor.completeDelivery(preparing, Instant.now()))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID));
        assertThatThrownBy(() -> fulfillmentProcessor.ship(Long.MAX_VALUE, Instant.now()))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.NOT_FOUND_ORDER));

        // 취소 요청 주문은 먼저 취소를 처리한다 — 취소 · 만료 주문도 출고하지 않는다. 범위 밖 시각이어도 상태 판정(409)이 먼저다
        for (OrderStatus status : List.of(OrderStatus.CANCEL_REQUESTED, OrderStatus.CANCELED, OrderStatus.EXPIRED)) {
            long id = create(status, false);
            assertThatThrownBy(() -> fulfillmentProcessor.ship(id, Instant.now().plus(1, ChronoUnit.DAYS)))
                .as(status.name())
                .isInstanceOfSatisfying(OrderException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
                    assertThat(exception.getMessage()).contains(status.getDisplayName());
                });
            assertThat(read(id).status()).isEqualTo(status);
            assertThat(read(id).shippedAt()).isNull();
            assertThat(historyCount(id)).isEqualTo(1);
        }

        assertThat(read(paid).status()).isEqualTo(OrderStatus.PAID);
        assertThat(read(preparing).status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(historyCount(paid)).isEqualTo(1);
        assertThat(historyCount(preparing)).isEqualTo(1);
    }

    @Test
    @DisplayName("바꿀 때 시각이 지금 기준 30일 전 ~ 5분 뒤 밖이면 400 ORDER_159 · 160, 배송 완료가 출고보다 앞서면 ORDER_161 이고 아무것도 쓰지 않는다")
    void rejectsFulfillmentTimesOutOfRangeWithoutWriting() {
        long preparing = create(OrderStatus.PREPARING, false);
        Instant now = Instant.now();

        assertFieldRejected(() -> fulfillmentProcessor.ship(preparing, now.plus(10, ChronoUnit.MINUTES)), "ORDER_159", "shippedAt");
        assertFieldRejected(() -> fulfillmentProcessor.ship(preparing, now.minus(31, ChronoUnit.DAYS)), "ORDER_159", "shippedAt");
        assertThat(read(preparing).status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(read(preparing).shippedAt()).isNull();
        assertThat(historyCount(preparing)).isEqualTo(1);

        // 시계 차이(5분 안)와 29일 지연은 받는다
        Instant shippedAt = now.plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        assertThat(fulfillmentProcessor.ship(preparing, shippedAt).changed()).isTrue();
        long late = create(OrderStatus.PREPARING, false);
        assertThat(fulfillmentProcessor.ship(late, now.minus(29, ChronoUnit.DAYS)).changed()).isTrue();

        assertFieldRejected(() -> fulfillmentProcessor.completeDelivery(preparing, now.plus(10, ChronoUnit.MINUTES)), "ORDER_160", "deliveredAt");
        assertFieldRejected(() -> fulfillmentProcessor.completeDelivery(preparing, now.minus(31, ChronoUnit.DAYS)), "ORDER_160", "deliveredAt");
        assertFieldRejected(() -> fulfillmentProcessor.completeDelivery(preparing, shippedAt.minusNanos(1_000)), "ORDER_161", "deliveredAt");
        assertThat(read(preparing).status()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(read(preparing).deliveredAt()).isNull();
        assertThat(historyCount(preparing)).isEqualTo(2);

        assertThat(fulfillmentProcessor.completeDelivery(preparing, shippedAt).changed()).as("출고와 같은 시각은 받는다").isTrue();
    }

    private static void assertFieldRejected(ThrowingCallable call, String code, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OrderFieldValidationException.class, exception ->
            assertThat(exception.violations()).singleElement().satisfies(violation -> {
                assertThat(violation.code()).isEqualTo(code);
                assertThat(violation.field()).isEqualTo(field);
            }));
    }

    @Test
    @DisplayName("고객 취소가 출고 반영의 읽기와 쓰기 사이에 먼저 커밋하면 출고 쪽이 버전 충돌로 실패한다 — 출고 시각 · 연동 이력이 남지 않는다")
    void concurrentCancelWinsOverShipmentAfterItsRead() throws Exception {
        long id = create(OrderStatus.PREPARING, false);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        OrderRepositoryPort target = AopTestUtils.getUltimateTargetObject(orders);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            loaded.countDown();
            if (!canceled.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Customer cancel did not complete");
            }
            return result;
        }).when(target).findById(id);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<OrderFulfillmentInfo> shipment = pool.submit(() -> fulfillmentProcessor.ship(id, Instant.now()));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            tx().executeWithoutResult(status -> assertThat(jdbc.update(
                "update orders.orders set status = 'CANCEL_REQUESTED', version = version + 1 where id = ? and version = 1 and status = 'PREPARING'", id)).isEqualTo(1));
            canceled.countDown();
            assertThatThrownBy(() -> shipment.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            canceled.countDown();
            pool.shutdownNow();
            reset(target);
        }
        Order stored = read(id);
        assertThat(stored.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(stored.shippedAt()).isNull();
        assertThat(historyCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("맞춤 제작 항목이 있으면 결제 완료 → 제작 중 → 상품 준비중으로 간다 — 상세의 다음 상태도 그에 맞다")
    void makingStageForCustomOrders() {
        long id = create(OrderStatus.PAID, true);
        assertThat(tx().execute(status -> processor.getDetail(id)).nextStatuses()).containsExactlyInAnyOrder(OrderStatus.MAKING, OrderStatus.PREPARING);

        assertThat(processor.changeStatus(id, new OrderStatusChangeCommand(OrderStatus.MAKING, null), ADMIN_ID)).isTrue();
        assertThat(tx().execute(status -> processor.getDetail(id)).nextStatuses()).containsExactly(OrderStatus.PREPARING);
        assertThat(processor.changeStatus(id, new OrderStatusChangeCommand(OrderStatus.PREPARING, null), ADMIN_ID)).isTrue();
        assertThat(read(id).status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(historyCount(id)).isEqualTo(3);
    }

    @Test
    @DisplayName("고객 취소가 어드민 처리의 읽기와 쓰기 사이에 먼저 커밋하면 어드민 쪽이 버전 충돌로 실패한다 — 하나만 성공하고 이력도 하나만 남는다")
    void concurrentCustomerCancelWinsOverAdminAfterItsRead() throws Exception {
        long id = create(OrderStatus.PAID, false);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        OrderRepositoryPort target = AopTestUtils.getUltimateTargetObject(orders);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            loaded.countDown();
            if (!canceled.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Customer cancel did not complete");
            }
            return result;
        }).when(target).findById(id);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> admin = pool.submit(() -> processor.changeStatus(id, new OrderStatusChangeCommand(OrderStatus.PREPARING, null), ADMIN_ID));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            // 고객 취소(#16 몫)의 커밋 — 같은 행을 버전 비교로 바꾼다(findById 는 위에서 막혀 있어 직접 쓴다)
            tx().executeWithoutResult(status -> assertThat(jdbc.update(
                "update orders.orders set status = 'CANCEL_REQUESTED', version = version + 1 where id = ? and version = 1 and status = 'PAID'", id)).isEqualTo(1));
            canceled.countDown();
            assertThatThrownBy(() -> admin.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            canceled.countDown();
            pool.shutdownNow();
            reset(target);
        }
        assertThat(read(id).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        List<OrderStatusHistoryQueryResult> histories = tx().execute(status -> orders.findHistories(id));
        assertThat(histories).extracting(OrderStatusHistoryQueryResult::changedByType)
            .as("어드민 이력은 롤백됐다").containsExactly(StatusChangedBy.CUSTOMER);
    }

    @Test
    @DisplayName("어드민 처리가 먼저 커밋하면 그 전에 읽은 고객 취소가 버전 충돌로 실패한다")
    void adminCommitFencesStaleCustomerCancel() {
        long id = create(OrderStatus.PAID, false);
        Order staleForCustomer = read(id);

        assertThat(processor.changeStatus(id, new OrderStatusChangeCommand(OrderStatus.PREPARING, null), ADMIN_ID)).isTrue();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> orders.saveStatusChange(
            staleForCustomer.changeStatus(OrderStatus.CANCEL_REQUESTED), customerCancelHistory(staleForCustomer))))
            .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(read(id).status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(historyCount(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("목록 — 최신순, 상태 · 기간으로 거르고, 주문 이름(첫 상품 외 N건) · 받는 분 · 결제 금액을 묶어 읽는다")
    void listsLatestFirstWithFilters() {
        long member = IDS.incrementAndGet();
        long older = place(member, OrderStatus.PAID, quoteLine(101, 1000, 1), quoteLine(102, 2000, 2));
        long newer = place(member, OrderStatus.PENDING_PAYMENT, quoteLine(103, 3000, 1));
        Instant before = Instant.now().minusSeconds(60);

        Page<OrderAdminSummaryInfo> paid = tx().execute(status ->
            processor.search(new OrderAdminSearchCommand(OrderStatusFilter.PAID, before, null, 0, 100)));
        OrderAdminSummaryInfo row = paid.getContent().stream().filter(result -> result.orderId() == older).findFirst().orElseThrow();
        assertThat(row.firstProductName()).isEqualTo("상품101");
        assertThat(row.itemCount()).isEqualTo(2);
        assertThat(row.recipientName()).isNotBlank();
        assertThat(row.totalAmount()).isPositive();
        assertThat(paid.getContent()).extracting(OrderAdminSummaryInfo::orderId).doesNotContain(newer);

        Page<OrderAdminSummaryInfo> all = tx().execute(status -> processor.search(new OrderAdminSearchCommand(null, before, null, 0, 100)));
        List<Long> ids = all.getContent().stream().map(OrderAdminSummaryInfo::orderId).filter(orderId -> orderId == older || orderId == newer).toList();
        assertThat(ids).containsExactly(newer, older);

        Page<OrderAdminSummaryInfo> future = tx().execute(status ->
            processor.search(new OrderAdminSearchCommand(null, Instant.now().plusSeconds(60), null, 0, 20)));
        assertThat(future.getContent()).isEmpty();
        Page<OrderAdminSummaryInfo> beyond = tx().execute(status -> processor.search(new OrderAdminSearchCommand(null, before, null, 1000, 20)));
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    private void assertRejected(long orderId, OrderStatus target, OrderErrorCode code) {
        assertThatThrownBy(() -> processor.changeStatus(orderId, new OrderStatusChangeCommand(target, null), ADMIN_ID))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private static OrderStatusHistory customerCancelHistory(Order before) {
        return new OrderStatusHistory(IDS.incrementAndGet(), before.id(), before.status(), OrderStatus.CANCEL_REQUESTED,
            StatusChangedBy.CUSTOMER, before.memberId(), "고객 변심");
    }

    private long create(OrderStatus status, boolean customOrder) {
        OrderQuoteLine line = customOrder
            ? OrderQuoteLine.of(101, 7101, "맞춤 두루마기", "M", krw(90_000), 1, OrderCustomOptions.empty(), true, WEIGHT_GRAMS, HS_CODE)
            : quoteLine(101, 1000, 1);
        return place(IDS.incrementAndGet(), status, line);
    }

    private long place(long memberId, OrderStatus status, OrderQuoteLine... lines) {
        long id = IDS.incrementAndGet();
        var order = Order.place(id, memberId, domesticQuote(lines), IDS::incrementAndGet, address("KR"), IDS.incrementAndGet(), Instant.now().plusSeconds(1800));
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            if (status != OrderStatus.PENDING_PAYMENT) {
                // 결제 완료(#14)는 아직 없다 — 상태만 직접 맞춘다(버전도 올려 읽은 값과 맞춘다)
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
}
