package com.commercefoundry.domainlayer.skucost.adapter.in.internal.presenter;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.item.SkuCostAtInternalItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.item.SkuCostInternalItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.response.SkuCostAtQueryInternalResponse;
import com.commercefoundry.domainlayer.skucost.application.info.SkuCostsAtInfo;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import java.util.List;
import org.springframework.stereotype.Component;

/** 서비스 간 시점 원가 응답. 식별자 {@code long → String}, 금액 {@code Money → MoneyItem}, 유형 → metadata 변환만 한다. */
@Component
public class SkuCostInternalPresenter {

    public SkuCostAtQueryInternalResponse toResponse(List<SkuCostsAtInfo> infos) {
        return SkuCostAtQueryInternalResponse.builder()
            .items(infos.stream().map(this::toItem).toList())
            .build();
    }

    private SkuCostAtInternalItem toItem(SkuCostsAtInfo info) {
        return SkuCostAtInternalItem.builder()
            .skuId(Long.toString(info.skuId()))
            .at(info.at())
            .costs(info.costs().stream().map(this::toCost).toList())
            .build();
    }

    private SkuCostInternalItem toCost(SkuCost cost) {
        return SkuCostInternalItem.builder()
            .costType(cost.costType().toMetadata())
            .amount(MoneyItem.of(cost.amount().amount(), cost.amount().currency().name()))
            .effectiveFrom(cost.effectiveFrom())
            .recordedAt(cost.recordedAt())
            .build();
    }
}
