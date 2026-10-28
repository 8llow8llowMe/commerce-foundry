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
import com.commercefoundry.persistence.util.ConstraintViolations;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRepositoryAdapter implements OrderRepositoryPort {

    /** {@code orders} 의 기본 키 — PostgreSQL 이 붙이는 이름이다(이름을 지정할 수 없다). */
    static final String ORDER_ID_CONSTRAINT = "orders_pkey";

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
     *   <li>주문 PK 위반 — 같은 주문 아이디로 다른 시도가 이미 저장했다 → {@link OrderSaveException.Reason#ALREADY_SAVED}(보상 해제가 없으므로 같은 주문을 재생하면 된다).
     *       같은 아이디의 이중 저장은 같은 재고 선점도 가리키지만, PostgreSQL 은 PK 인덱스를 먼저 만들고 INSERT 때 그 순서로 검사해 PK 위반으로 나온다</li>
     *   <li>그 밖의 DB 거절 — {@link OrderSaveException.Reason#REJECTED}. 다른 주문 아이디가 같은 재고 선점을 가리킨 경우({@code uk_orders_stock_reservation_id})도
     *       여기다 — 재생할 주문이 아니라 데이터 이상이다. check 위반은 금액 · 스냅숏 조립 버그다</li>
     * </ul>
     *
     * <p>원인 예외는 싣지 않는다 — 제약 위반 · 배치 실패 메시지에 행 값(배송지 · 맞춤 입력)이 들어 있다. 로그에는 주문 아이디 · 예외 타입만 남긴다.
     * 호출 Processor의 트랜잭션을 필수로 요구하여 뒤쪽 스냅숏 · 이력 저장 실패도 주문과 함께 롤백한다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Order insert(Order order, OrderStatusHistory history) {
        if (!order.isNew() || order.createdAt() != null || order.status() != OrderStatus.PENDING_PAYMENT
            || history.orderId() != order.id() || history.fromStatus() != null || history.toStatus() != OrderStatus.PENDING_PAYMENT
            || history.changedByType() != StatusChangedBy.CUSTOMER || !Long.valueOf(order.memberId()).equals(history.changedById())) {
            throw new IllegalArgumentException("New order and creation history must match");
        }
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
            String causeType = exception.getClass().getSimpleName();
            if (ConstraintViolations.violates(exception, ORDER_ID_CONSTRAINT)) {
                log.info("order already saved by another attempt. orderId={}", order.id());
                throw new OrderSaveException(OrderSaveException.Reason.ALREADY_SAVED, causeType);
            }
            log.error("order save rejected. orderId={}, type={}", order.id(), causeType);
            throw new OrderSaveException(OrderSaveException.Reason.REJECTED, causeType);
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
