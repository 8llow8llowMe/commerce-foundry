package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.ProductSizeChartEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface ProductSizeChartRepository extends JpaRepository<ProductSizeChartEntity, Long> {

    Optional<ProductSizeChartEntity> findByProductId(long productId);

    boolean existsByTemplateId(long templateId);

    /** 같은 상품 표를 고치는 쓰기가 줄을 선다. 잠금 대기는 {@link #LOCK_TIMEOUT_MILLIS} 까지다. */
    String LOCK_TIMEOUT_MILLIS = "2000";

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    @Query("select chart from ProductSizeChartEntity chart where chart.productId = :productId")
    Optional<ProductSizeChartEntity> findByProductIdForUpdate(long productId);
}
