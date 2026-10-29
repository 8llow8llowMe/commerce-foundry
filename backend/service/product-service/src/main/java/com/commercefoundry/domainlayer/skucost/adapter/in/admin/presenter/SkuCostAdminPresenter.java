package com.commercefoundry.domainlayer.skucost.adapter.in.admin.presenter;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.item.SkuCostAdminItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.response.SkuCurrentCostsAdminResponse;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 어드민 SKU 원가 응답. 식별자 {@code long → String}, 금액 {@code Money → MoneyItem}, 유형 → metadata 변환만 한다. */
@Component
public class SkuCostAdminPresenter {

    public SkuCurrentCostsAdminResponse toCurrent(long skuId, List<SkuCost> costs) {
        return SkuCurrentCostsAdminResponse.builder()
            .skuId(Long.toString(skuId))
            .costs(costs.stream().map(this::toItem).toList())
            .build();
    }

    public PageResponse<SkuCostAdminItem> toPage(Page<SkuCost> page) {
        return PageResponse.of(page).map(this::toItem);
    }

    public SkuCostAdminItem toItem(SkuCost cost) {
        return SkuCostAdminItem.builder()
            .costId(Long.toString(cost.id()))
            .costType(cost.costType().toMetadata())
            // 상품 컨텍스트의 MoneyItems 와 같은 변환이다 — 다른 컨텍스트의 진입 support 는 쓰지 않는다 (H8)
            .amount(MoneyItem.of(cost.amount().amount(), cost.amount().currency().name()))
            .effectiveFrom(cost.effectiveFrom())
            .reason(cost.reason())
            .adminMemberId(Long.toString(cost.adminMemberId()))
            .createdAt(cost.recordedAt())
            .build();
    }
}
