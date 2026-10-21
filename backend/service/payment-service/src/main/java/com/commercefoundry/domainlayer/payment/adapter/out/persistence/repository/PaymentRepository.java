package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    /** {@code uk_payment_pg_order_id} 로 찾는다 — 웹훅 · successUrl 이 PG 주문번호로 결제 시도를 가리킨다. */
    Optional<PaymentEntity> findByPgOrderId(String pgOrderId);

    /** {@code uk_payment_active_order_id} 로 찾는다 — 주문당 활성(FAILED · ABORTED 아닌) 결제 시도는 많아야 하나다. */
    Optional<PaymentEntity> findByActiveOrderId(Long activeOrderId);
}
