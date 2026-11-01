package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.OrderAdminDetail;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.info.OrderAdminSummaryInfo;
import com.commercefoundry.domainlayer.order.application.info.OrderStatusHistoryInfo;
import com.commercefoundry.domainlayer.order.domain.model.AdminStatusChangePolicy;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 주문 — 목록 · 상세 · 상태 변경 (#17). 어떤 전이를 해도 되는지는 {@link AdminStatusChangePolicy} 가 정하고, 표 검사는 도메인 {@code changeStatus} 가 다시 한다.
 *
 * <p>상태 변경은 주문마다 새 트랜잭션({@code REQUIRES_NEW}, 5초)이다 — 일괄 변경의 한 건 실패가 다른 건을 되돌리지 않는다. 읽은 뒤 다른 곳(고객 취소 · 결제 · 다른 운영자)이
 * 먼저 커밋했으면 merge 의 버전 비교가 {@code OptimisticLockingFailureException} 으로 이 트랜잭션을 되돌린다 — 둘 중 하나만 성공한다.
 */
@Component
@RequiredArgsConstructor
public class OrderAdminProcessor {

    private final OrderRepositoryPort orderRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Transactional(readOnly = true, timeout = 5)
    public Page<OrderAdminSummaryInfo> search(OrderAdminSearchCommand command) {
        return orderRepositoryPort.findAdminSummaryPage(command.status() == null ? null : command.status().toOrderStatus(), command.fromOrOpen(), command.toOrOpen(),
            command.page(), command.size()).map(OrderAdminSummaryInfo::from);
    }

    /** 없는 주문이면 {@code NOT_FOUND_ORDER}(404). */
    @Transactional(readOnly = true, timeout = 5)
    public OrderAdminDetail getDetail(long orderId) {
        Order order = orderRepositoryPort.findById(orderId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
        return new OrderAdminDetail(order, orderRepositoryPort.findHistories(orderId).stream().map(OrderStatusHistoryInfo::from).toList(),
            AdminStatusChangePolicy.manualTargets(order));
    }

    /**
     * 주문 하나의 상태를 바꾼다.
     *
     * @return 바꿨으면 true, 이미 그 상태라 바꾸지 않았으면 false(성공으로 본다 — 재시도 · 일괄 재실행이 안전하다)
     * @throws OrderException 없는 주문 {@code NOT_FOUND_ORDER} · 표 밖 {@code ORDER_STATUS_TRANSITION_INVALID} · 다른 흐름의 전이
     *     {@code ADMIN_STATUS_CHANGE_NOT_ALLOWED} · 맞춤 제작 항목 없이 제작 중 {@code MAKING_REQUIRES_CUSTOM_ORDER_ITEM}
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 곳이 먼저 바꿨을 때 (이 트랜잭션은 롤백)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean changeStatus(long orderId, OrderStatusChangeCommand command, long adminId) {
        Order order = orderRepositoryPort.findById(orderId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
        return switch (AdminStatusChangePolicy.decide(order, command.status())) {
            case ALREADY -> false;
            case NOT_IN_TABLE -> throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, order.status().getDisplayName());
            case NOT_MANUAL -> throw new OrderException(OrderErrorCode.ADMIN_STATUS_CHANGE_NOT_ALLOWED, command.status().getDisplayName());
            case MAKING_WITHOUT_CUSTOM_ITEM -> throw new OrderException(OrderErrorCode.MAKING_REQUIRES_CUSTOM_ORDER_ITEM);
            case ALLOWED -> {
                if (command.status() == OrderStatus.DELIVERED && command.reason() == null) {
                    throw new OrderException(OrderErrorCode.DELIVERED_REASON_REQUIRED);
                }
                Order changed = order.changeStatus(command.status());
                orderRepositoryPort.saveStatusChange(changed,
                    OrderStatusHistory.changedByAdmin(snowflakeIdGenerator.generateId(), order, changed, adminId, command.reason()));
                yield true;
            }
        };
    }
}
