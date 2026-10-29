package com.commercefoundry.domainlayer.skucost.application.service.processor;

import com.commercefoundry.domainlayer.skucost.application.port.out.SkuCostRepositoryPort;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostTimeline;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * SKU 원가 조회 — 지금 적용되는 유형별 원가와 이력 페이지. SKU 가 있는지는 Facade 가 상품 컨텍스트로 먼저 본다.
 */
@Component
@RequiredArgsConstructor
public class SkuCostQueryProcessor {

    private final SkuCostRepositoryPort skuCostRepositoryPort;
    private final Clock clock;

    /**
     * 지금 적용되는 유형별 원가 — 유형 선언 순서이고 미입력 유형은 빠진다. 질의가 유형별 최신 행을 골라 오고,
     * {@link SkuCostTimeline#resolveAt} 이 같은 규칙으로 한 번 더 걸러 유형당 하나 · 순서를 보장한다.
     */
    public List<SkuCost> getCurrent(long skuId) {
        Instant now = clock.instant();
        return SkuCostTimeline.resolveAt(skuCostRepositoryPort.findCurrent(skuId, now), now);
    }

    /** @param costType null 이면 모든 유형 */
    public Page<SkuCost> getHistories(long skuId, SkuCostType costType, int page, int size) {
        return skuCostRepositoryPort.findHistoryPage(skuId, costType, page, size);
    }
}
