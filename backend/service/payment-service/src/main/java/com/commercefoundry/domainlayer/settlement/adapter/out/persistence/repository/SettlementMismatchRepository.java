package com.commercefoundry.domainlayer.settlement.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity.SettlementMismatchEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementMismatchRepository extends JpaRepository<SettlementMismatchEntity, Long> {

    /** {@code uk_settlement_mismatch_payment_key_type} 의 앞 칸으로 찾는다. 호출자가 목록을 나눠 IN 절 크기를 제한한다. */
    List<SettlementMismatchEntity> findAllByPaymentKeyIn(Collection<String> paymentKeys);
}
