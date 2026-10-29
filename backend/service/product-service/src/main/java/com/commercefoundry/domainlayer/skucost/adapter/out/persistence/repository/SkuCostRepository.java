package com.commercefoundry.domainlayer.skucost.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.skucost.adapter.out.persistence.entity.SkuCostEntity;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * SKU 원가 적용 이력 (append-only). 추가와 조회만 연다 — {@code JpaRepository} 를 상속하지 않아 {@code delete*} · {@code saveAll} 이 없다
 * (선례: auth {@code AdminAuditLogRepository}). 정렬은 메서드 이름 · 질의가 고정한다 — {@code pageable} 에는 정렬을 싣지 않는다.
 */
public interface SkuCostRepository extends Repository<SkuCostEntity, Long> {

    /** 새 행 하나. {@code Persistable} 이라 SELECT 없이 persist 하고, 바로 flush 해 유니크 위반을 이 호출에서 드러낸다. */
    SkuCostEntity saveAndFlush(SkuCostEntity entity);

    /** 같은 SKU · 유형의 최신 행 — 유니크 인덱스 {@code (sku_id, cost_type, effective_from)} 를 역순으로 한 행 읽는다. */
    Optional<SkuCostEntity> findFirstBySkuIdAndCostTypeOrderByEffectiveFromDesc(Long skuId, SkuCostType costType);

    /**
     * 시각 {@code at} 에 적용되는 유형별 행 — 유형마다 {@code effective_from <= at} 중 가장 늦은 행. 유니크 키가 같은 시작을 막아 유형당 한 행이다.
     * 상관 서브쿼리의 max 는 유니크 인덱스 접두 {@code (sku_id, cost_type)} 를 탄다.
     */
    @Query("""
        select cost from SkuCostEntity cost
        where cost.skuId = :skuId
          and cost.effectiveFrom = (
            select max(latest.effectiveFrom) from SkuCostEntity latest
            where latest.skuId = cost.skuId and latest.costType = cost.costType and latest.effectiveFrom <= :at
          )
        """)
    List<SkuCostEntity> findCurrentBySkuId(long skuId, Instant at);

    /** 모든 유형의 이력 — 적용 시작 내림차순 · 아이디 내림차순. 파생 count 는 {@code where sku_id = ?} 로 유니크 인덱스 접두를 탄다. */
    Page<SkuCostEntity> findAllBySkuIdOrderByEffectiveFromDescIdDesc(Long skuId, Pageable pageable);

    /** 한 유형의 이력 — 적용 시작 내림차순 · 아이디 내림차순. 유니크 인덱스를 역순으로 읽는다. */
    Page<SkuCostEntity> findAllBySkuIdAndCostTypeOrderByEffectiveFromDescIdDesc(Long skuId, SkuCostType costType, Pageable pageable);
}
