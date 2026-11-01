package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderItemEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    List<OrderItemEntity> findAllByOrderIdOrderByLineNoAsc(Long orderId);

    /** 어드민 목록 한 페이지의 항목 — {@code uk_order_item_order_id_line_no} 의 선두 {@code order_id} 로 묶어 읽는다 (#17). */
    List<OrderItemEntity> findAllByOrderIdInOrderByOrderIdAscLineNoAsc(Collection<Long> orderIds);
}
