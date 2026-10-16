package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.SkuInternalResponse;
import java.util.List;

public interface SkuInternalUseCase {

    /**
     * 서비스 간 SKU 벌크 조회 ({@code api-design-guide.md} §1-3). 중복은 첫 등장 순서로 제거하고, 없는 아이디는 응답에서 뺀다.
     *
     * @param skuIds 검증을 통과한 1~100개의 양수 아이디
     */
    SkuInternalResponse getSkus(List<Long> skuIds);
}
