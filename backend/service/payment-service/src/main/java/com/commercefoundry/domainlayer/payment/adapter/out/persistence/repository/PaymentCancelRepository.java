package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancelEntity, Long> {
    List<PaymentCancelEntity> findAllByPaymentId(Long paymentId);

    /** 정산 대사(#219) — 결제 아이디 목록의 취소 원장 행 전부({@code idx_payment_cancel_payment_id}). 호출자가 목록을 나눠 IN 절 크기를 제한한다. */
    List<PaymentCancelEntity> findAllByPaymentIdIn(Collection<Long> paymentIds);
}
