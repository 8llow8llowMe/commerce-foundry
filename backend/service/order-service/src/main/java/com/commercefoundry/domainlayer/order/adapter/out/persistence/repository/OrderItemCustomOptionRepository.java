package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderItemCustomOptionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemCustomOptionRepository extends JpaRepository<OrderItemCustomOptionEntity, Long> {

    List<OrderItemCustomOptionEntity> findAllByOrderIdOrderByOrderItemIdAscDisplayOrderAsc(Long orderId);
}
