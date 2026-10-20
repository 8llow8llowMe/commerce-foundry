package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockRestorationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRestorationRepository extends JpaRepository<StockRestorationEntity, Long> {

    /** 클레임의 복원 — {@code uk_stock_restoration_claim_id} 로 찾는다. */
    Optional<StockRestorationEntity> findByClaimId(Long claimId);
}
