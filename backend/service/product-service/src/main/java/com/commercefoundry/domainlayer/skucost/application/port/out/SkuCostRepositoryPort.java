package com.commercefoundry.domainlayer.skucost.application.port.out;

import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/**
 * SKU 원가 적용 이력 (append-only). 추가와 조회만 있다 — 고치거나 지우는 메서드를 두지 않는다.
 */
public interface SkuCostRepositoryPort {

    /**
     * 새 행을 persist 하고 바로 flush 한다. 같은 SKU · 유형 · 적용 시작 시각이 먼저 커밋됐으면 409 {@code SKU_COST_004} 이다.
     *
     * @return 기록 시각({@code recordedAt})이 채워진 행
     */
    SkuCost insert(SkuCost cost);

    /** 같은 SKU · 유형에서 적용 시작이 가장 늦은 행. 없으면 비어 있다. */
    Optional<SkuCost> findLatest(long skuId, SkuCostType costType);

    /** 시각 {@code at} 에 적용되는 유형별 행 — 유형마다 {@code effectiveFrom <= at} 중 가장 늦은 행이다. 한 번의 질의다. */
    List<SkuCost> findCurrent(long skuId, Instant at);

    /**
     * 이력 한 페이지. 적용 시작 내림차순 · 아이디 내림차순으로 고정한다.
     *
     * @param costType null 이면 모든 유형
     * @param page     0 부터 센다
     */
    Page<SkuCost> findHistoryPage(long skuId, SkuCostType costType, int page, int size);
}
