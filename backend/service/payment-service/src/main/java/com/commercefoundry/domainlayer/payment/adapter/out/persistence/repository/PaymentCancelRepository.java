package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancelEntity, Long> {
    List<PaymentCancelEntity> findAllByPaymentId(Long paymentId);
}
