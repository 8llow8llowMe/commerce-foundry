package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.OrderMemberSummaryInfo;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 주문 내역 · 상세 · 결제 전 취소 (#16 PR-1). 주문은 늘 회원 아이디와 함께 읽는다 — 남의 주문은 없는 주문({@code NOT_FOUND_ORDER})이다.
 *
 * <p>결제 전 취소는 주문 상태 · {@code CUSTOMER} 이력 · {@code order-canceled} outbox 가 한 트랜잭션(5초)이다 — 함께 커밋되거나 함께 롤백된다
 * ({@code order-flow-guide.md} §3-5). 읽은 뒤 결제 완료 · 만료가 먼저 커밋했으면 merge 의 버전 비교가 {@code OptimisticLockingFailureException} 으로 되돌린다.
 */
@Component
@RequiredArgsConstructor
public class OrderHistoryProcessor {

    private final OrderRepositoryPort orderRepositoryPort;
    private final OrderEventPort orderEventPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Transactional(readOnly = true, timeout = 5)
    public Slice<OrderMemberSummaryInfo> findSlice(long memberId, int page, int size) {
        return orderRepositoryPort.findMemberSummarySlice(memberId, page, size).map(OrderMemberSummaryInfo::from);
    }

    /** 없거나 남의 주문이면 {@code NOT_FOUND_ORDER}(404). */
    @Transactional(readOnly = true, timeout = 5)
    public Order get(long memberId, long orderId) {
        return orderRepositoryPort.findByIdAndMemberId(orderId, memberId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
    }

    /**
     * 결제 전 취소.
     *
     * @return 취소된 주문 — 이미 취소돼 있었으면 읽은 그대로(아무것도 쓰지 않는다)
     * @throws OrderException 없거나 남의 주문 {@code NOT_FOUND_ORDER} · 결제 대기가 아닌 상태 {@code ORDER_STATUS_TRANSITION_INVALID}
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 곳이 먼저 바꿨을 때 (이 트랜잭션은 롤백)
     */
    @Transactional(timeout = 5)
    public Order cancel(long memberId, long orderId, OrderCancelReason reason) {
        Order order = get(memberId, orderId);
        if (order.status() == OrderStatus.CANCELED) {
            return order;
        }
        if (!order.isCancelableByCustomer()) {
            throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, order.status().getDisplayName());
        }
        Order canceled = order.cancelBeforePayment();
        orderRepositoryPort.saveCustomerCancel(canceled, OrderStatusHistory.canceledByCustomer(snowflakeIdGenerator.generateId(), order, reason));
        orderEventPort.canceled(order.id(), order.stockReservationId(), reason);
        return canceled;
    }
}
