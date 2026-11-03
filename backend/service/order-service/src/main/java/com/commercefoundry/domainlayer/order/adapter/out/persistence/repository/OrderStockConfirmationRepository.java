package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderStockConfirmationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 결제 반영의 재선점 증거 (#308 PR-C1). PK(결제 아이디)로만 읽는다. */
public interface OrderStockConfirmationRepository extends JpaRepository<OrderStockConfirmationEntity, Long> {
}
