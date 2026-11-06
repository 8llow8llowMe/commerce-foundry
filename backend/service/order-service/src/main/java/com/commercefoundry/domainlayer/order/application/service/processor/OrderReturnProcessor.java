package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.OrderReturnCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 부모 주문을 먼저 잠그고 반품 접수·수량 예약·최초 상태 이력을 같은 트랜잭션에 쓴다. 외부 호출은 없다. */
@Component
@RequiredArgsConstructor
public class OrderReturnProcessor {
    private final OrderRepositoryPort orderRepositoryPort;
    private final ClaimRepositoryPort claimRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Transactional(timeout = 5)
    public Result request(long memberId, long orderId, UUID requestKey, OrderReturnCommand command) {
        Order order = orderRepositoryPort.findByIdAndMemberIdForUpdate(orderId, memberId)
            .orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
        var existing = claimRepositoryPort.findByOrderIdAndRequestKey(orderId, requestKey);
        // 소유 확인 뒤 재생을 먼저 판단한다. 주문 상태나 남은 수량이 바뀌어도 동일 접수는 재생된다.
        if (existing.isPresent()) {
            // 같은 주문 · 같은 키의 결제 후 취소 클레임(#16, 결정적 키)을 반품 재생으로 돌려주지 않는다 — 키 재사용으로 거절한다
            if (existing.get().type() != Claim.Type.RETURN || !command.matches(existing.get().items())) {
                throw new OrderException(OrderErrorCode.RETURN_KEY_REUSED);
            }
            return new Result(false, existing.get());
        }
        if (order.status() != OrderStatus.DELIVERED && order.status() != OrderStatus.RETURN_REQUESTED) {
            throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, order.status().getDisplayName());
        }
        requireQuantities(order, command);
        if (order.status() == OrderStatus.DELIVERED) {
            Order requested = order.changeStatus(OrderStatus.RETURN_REQUESTED);
            orderRepositoryPort.saveStatusChange(requested, new OrderStatusHistory(snowflakeIdGenerator.generateId(), order.id(),
                OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED, StatusChangedBy.CUSTOMER, memberId, "CUSTOMER_RETURN_REQUESTED"));
        }
        var items = command.items().stream().map(item -> new ClaimItem(snowflakeIdGenerator.generateId(), item.orderItemId(), item.quantity(), item.reason())).toList();
        Claim claim = new Claim(snowflakeIdGenerator.generateId(), orderId, requestKey, Claim.Type.RETURN, Claim.Status.REQUESTED, null, items, null, null);
        return new Result(true, claimRepositoryPort.insert(claim));
    }

    private void requireQuantities(Order order, OrderReturnCommand command) {
        Map<Long, Integer> ordered = new HashMap<>();
        for (int i = 0; i < order.itemIds().size(); i++) {
            ordered.put(order.itemIds().get(i), order.quote().lines().get(i).quantity());
        }
        if (command.items().stream().map(OrderReturnCommand.Item::orderItemId).distinct().count() != command.items().size()
            || command.items().stream().anyMatch(item -> !ordered.containsKey(item.orderItemId()))) {
            throw new OrderException(OrderErrorCode.RETURN_ITEM_INVALID);
        }
        Map<Long, Long> reserved = claimRepositoryPort.sumRequestedReturnQuantities(order.id());
        if (command.items().stream().anyMatch(item -> reserved.getOrDefault(item.orderItemId(), 0L) + item.quantity() > ordered.get(item.orderItemId()))) {
            throw new OrderException(OrderErrorCode.RETURN_QUANTITY_EXCEEDED);
        }
    }

    public record Result(boolean created, Claim claim) {
    }
}
