package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.SkuCustomRepository;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface SkuRepository extends JpaRepository<SkuEntity, Long>, SkuCustomRepository {

    /** SKU 기준 쓰기(원가 이력 추가)가 줄을 선다. 잠금 대기는 {@link #LOCK_TIMEOUT_MILLIS} 까지다 ({@code coding-conventions.md} §9-1). */
    String LOCK_TIMEOUT_MILLIS = "2000";

    List<SkuEntity> findAllByProductIdOrderByIdAsc(Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    @Query("select sku from SkuEntity sku where sku.id = :skuId")
    Optional<SkuEntity> findByIdForUpdate(long skuId);
}
