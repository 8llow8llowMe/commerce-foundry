package com.commercefoundry.domainlayer.skucost.application.port.in;

import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.item.SkuCostAdminItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.response.SkuCurrentCostsAdminResponse;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostCreateCommand;
import com.commercefoundry.domainlayer.skucost.application.model.SkuCostTypeCode;
import com.commercefoundry.persistence.dto.PageResponse;

public interface SkuCostAdminUseCase {

    /** 지금 적용되는 유형별 원가. 미입력 유형은 빠진다. */
    SkuCurrentCostsAdminResponse getCurrentCosts(long skuId);

    /** @param costType null 이면 모든 유형 */
    PageResponse<SkuCostAdminItem> getHistories(long skuId, SkuCostTypeCode costType, int page, int size);

    SkuCostAdminItem createCost(SkuCostCreateCommand command);
}
