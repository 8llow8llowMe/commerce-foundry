package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderItemEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    List<OrderItemEntity> findAllByOrderIdOrderByLineNoAsc(Long orderId);

    /** 여러 주문의 항목을 한 번에 — 주문 · 줄 번호 순 (#16 내역 · #17 어드민 목록). {@code uk_order_item_order_id_line_no} 를 탄다. */
    List<OrderItemEntity> findAllByOrderIdInOrderByOrderIdAscLineNoAsc(Collection<Long> orderIds);
}
