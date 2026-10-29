package com.commercefoundry.domainlayer.skucost.application.port.in;

import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.response.SkuCostAtQueryInternalResponse;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostAtQueryCommand;

public interface SkuCostInternalUseCase {

    /**
     * 서비스 간 시점 원가 일괄 조회 ({@code api-design-guide.md} §1-3 · §2-12). 쌍마다 그 시각에 적용되는 유형별 원가이고, 없는 SKU 는 응답에서 뺀다.
     *
     * @param command 정규화한(마이크로초 · 중복 제거) 1~100개의 쌍
     */
    SkuCostAtQueryInternalResponse getCostsAt(SkuCostAtQueryCommand command);
}
