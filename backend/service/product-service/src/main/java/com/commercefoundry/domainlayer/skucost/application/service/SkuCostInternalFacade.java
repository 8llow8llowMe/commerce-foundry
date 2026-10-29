package com.commercefoundry.domainlayer.skucost.application.service;

import com.commercefoundry.domainlayer.product.application.service.processor.SkuLookupProcessor;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.response.SkuCostAtQueryInternalResponse;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.presenter.SkuCostInternalPresenter;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostAtQueryCommand;
import com.commercefoundry.domainlayer.skucost.application.port.in.SkuCostInternalUseCase;
import com.commercefoundry.domainlayer.skucost.application.service.processor.SkuCostQueryProcessor;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 간 시점 원가 조회 (호출자 operations-service — 공헌이익 집계 #362). SKU 가 있는지는 상품 컨텍스트 {@link SkuLookupProcessor} 로 in 절 한 번에 보고,
 * 없는 SKU 의 쌍은 응답에서 뺀다 — 404 로 전체를 실패시키지 않는다 ({@code api-design-guide.md} §1-3). 있는 SKU 는 원가가 없어도 빈 목록으로 남겨
 * "SKU 없음" 과 "원가 미입력" 을 구분한다. 외부 I/O 가 없는 읽기라 Facade 에 읽기 전용 트랜잭션을 건다.
 */
@Service
@RequiredArgsConstructor
public class SkuCostInternalFacade implements SkuCostInternalUseCase {

    private final SkuLookupProcessor skuLookupProcessor;
    private final SkuCostQueryProcessor skuCostQueryProcessor;
    private final SkuCostInternalPresenter skuCostInternalPresenter;

    @Override
    @Transactional(readOnly = true)
    public SkuCostAtQueryInternalResponse getCostsAt(SkuCostAtQueryCommand command) {
        Set<Long> existingSkuIds = skuLookupProcessor.findExistingIds(command.skuIds());
        List<SkuCostAtQueryCommand.Point> points = command.points().stream().filter(point -> existingSkuIds.contains(point.skuId())).toList();
        return skuCostInternalPresenter.toResponse(skuCostQueryProcessor.getCostsAt(points));
    }
}
