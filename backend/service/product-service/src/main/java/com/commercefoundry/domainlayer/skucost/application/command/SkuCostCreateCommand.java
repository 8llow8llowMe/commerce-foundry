package com.commercefoundry.domainlayer.skucost.application.command;

import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import lombok.Builder;

/**
 * SKU 원가 이력 한 행 추가.
 *
 * @param effectiveFrom 적용 시작 시각. null 이면 서버 시각(지금)이다
 * @param reason        앞뒤 공백을 뺀 사유
 * @param adminMemberId access 토큰의 memberId — 본문으로 받지 않는다
 */
@Builder
public record SkuCostCreateCommand(
    long skuId,
    SkuCostType costType,
    Money amount,
    Instant effectiveFrom,
    String reason,
    long adminMemberId
) {
}
