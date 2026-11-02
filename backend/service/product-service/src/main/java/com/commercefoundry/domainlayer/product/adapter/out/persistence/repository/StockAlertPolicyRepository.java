package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAlertPolicyEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.StockAlertPolicyCustomRepository;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface StockAlertPolicyRepository extends JpaRepository<StockAlertPolicyEntity, Long>, StockAlertPolicyCustomRepository {

    /** SKU 잠금 안에서만 호출한다. 기존 생성 시각을 보존하고 변경 가능한 정책 칸만 갱신한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StockAlertPolicyEntity policy set policy.thresholdQuantity = :thresholdQuantity, policy.updatedAt = :updatedAt where policy.skuId = :skuId")
    int updatePolicy(long skuId, long thresholdQuantity, Instant updatedAt);
}
