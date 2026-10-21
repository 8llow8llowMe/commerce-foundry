package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderItemCustomOptionEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderItemEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderShippingAddressEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderItemCustomOptionRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderItemRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderShippingAddressRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStatusHistoryRepository;
import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapper;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.global.persistence.ConstraintViolations;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRepositoryAdapter implements OrderRepositoryPort {

    /** {@code orders} 의 기본 키 — PostgreSQL 이 붙이는 이름이다(이름을 지정할 수 없다). */
    static final String ORDER_ID_CONSTRAINT = "orders_pkey";
    /** {@code OrderEntity} 의 {@code @UniqueConstraint} 이름과 같다. */
    static final String STOCK_RESERVATION_CONSTRAINT = "uk_orders_stock_reservation_id";

    private final OrderRepository orderRepository;
    private final OrderShippingAddressRepository orderShippingAddressRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderItemCustomOptionRepository orderItemCustomOptionRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OrderMapper orderMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 주문 행을 {@code saveAndFlush} 로 바로 넣고(버전 null → persist) 그 자리에서 제약 위반을 가른다 — 커밋 때 터지면 Facade 밖에서 나 바꿀 자리가 없다
     * ({@code coding-conventions.md} §9-1). 배송지 · 항목 · 입력 · 이력은 {@code Persistable} 이라 SELECT 없이 넣고(배치 insert) 마지막에 flush 한다.
     *
     * <ul>
     *   <li>주문 PK · 재고 선점 유니크 위반 — 같은 주문 아이디로 다른 시도가 이미 저장했다. 같은 아이디 이중 저장이면 둘이 동시에 위반될 수 있어 둘 다
     *       {@link OrderSaveException.Reason#ALREADY_SAVED} 다(보상 해제가 없으므로 같은 주문을 재생하면 된다)</li>
     *   <li>그 밖의 DB 거절 — {@link OrderSaveException.Reason#REJECTED}. 원인 예외는 싣지 않는다 — 제약 위반 메시지에 행 값(배송지)이 들어 있다</li>
     * </ul>
     */
    @Override
    public Order insert(Order order, OrderStatusHistory history) {
        OrderEntity saved;
        List<OrderItemEntity> items = orderMapper.toItemEntities(order);
        List<OrderItemCustomOptionEntity> options = orderMapper.toCustomOptionEntities(order, snowflakeIdGenerator::generateId);
        OrderShippingAddressEntity address = orderMapper.toShippingAddressEntity(order);
        try {
            saved = orderRepository.saveAndFlush(orderMapper.toEntity(order));
            orderShippingAddressRepository.save(address);
            orderItemRepository.saveAll(items);
            orderItemCustomOptionRepository.saveAll(options);
            orderStatusHistoryRepository.save(orderMapper.toEntity(history));
            orderRepository.flush();
        } catch (DataAccessException | PersistenceException exception) {
            if (ConstraintViolations.violates(exception, ORDER_ID_CONSTRAINT) || ConstraintViolations.violates(exception, STOCK_RESERVATION_CONSTRAINT)) {
                log.info("order already saved by another attempt. orderId={}", order.id());
                throw new OrderSaveException(OrderSaveException.Reason.ALREADY_SAVED, exception.getClass().getSimpleName());
            }
            log.error("order save rejected. orderId={}, type={}", order.id(), exception.getClass().getSimpleName());
            throw new OrderSaveException(OrderSaveException.Reason.REJECTED, exception.getClass().getSimpleName());
        }
        return orderMapper.toDomain(saved, address, items, options.stream().collect(Collectors.groupingBy(OrderItemCustomOptionEntity::getOrderItemId)));
    }

    /** 주문 1번 + 배송지 1번 + 항목 1번 + 입력 1번 (모두 주문 아이디 하나로). */
    @Override
    public Optional<Order> findByIdAndMemberId(long orderId, long memberId) {
        return orderRepository.findByIdAndMemberId(orderId, memberId).map(entity -> {
            OrderShippingAddressEntity address = orderShippingAddressRepository.findById(entity.getId())
                .orElseThrow(() -> new IllegalStateException("주문의 배송지가 없습니다: orderId=" + entity.getId()));
            List<OrderItemEntity> items = orderItemRepository.findAllByOrderIdOrderByLineNoAsc(entity.getId());
            Map<Long, List<OrderItemCustomOptionEntity>> options = orderItemCustomOptionRepository.findAllByOrderIdOrderByOrderItemIdAscDisplayOrderAsc(entity.getId())
                .stream().collect(Collectors.groupingBy(OrderItemCustomOptionEntity::getOrderItemId));
            return orderMapper.toDomain(entity, address, items, options);
        });
    }
}
