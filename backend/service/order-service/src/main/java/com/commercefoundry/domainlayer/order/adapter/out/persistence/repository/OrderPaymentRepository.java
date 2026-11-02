package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderPaymentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 결제 반영 기록 (#308). */
public interface OrderPaymentRepository extends JpaRepository<OrderPaymentEntity, Long> {

    /** 유니크 키 {@code uk_order_payment_payment_id} 로 찾는다. */
    Optional<OrderPaymentEntity> findByPaymentId(Long paymentId);

    /** 주문의 결제 완료 기록 — 유니크 키 {@code uk_order_payment_paid_order_id} 로 찾는다. */
    Optional<OrderPaymentEntity> findByPaidOrderId(Long paidOrderId);
}
