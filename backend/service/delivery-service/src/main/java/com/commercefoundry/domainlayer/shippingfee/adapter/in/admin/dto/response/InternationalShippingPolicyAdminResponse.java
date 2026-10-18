package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingZoneAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "어드민 해외 배송비 정책")
public record InternationalShippingPolicyAdminResponse(

    @Schema(description = "배송비 정책 아이디", example = "4611686018427387904")
    String shippingPolicyId,

    @Schema(description = "기본료 — 모든 해외 배송에 무게 요금과 함께 붙는다. 이 통화가 정책 통화이고, 저장한 뒤에는 바꿀 수 없다")
    MoneyItem baseFee,

    @Schema(description = "무료배송 기준 주문 금액(할인 후 상품 합계) — 이 금액 이상이면 기본료 · 무게 요금이 모두 없다. null 이면 무료배송 없음", nullable = true)
    MoneyItem freeShippingThreshold,

    @Schema(description = "관세 · 부가세 고객 부담 안내 문구. 없으면 null", nullable = true)
    String customsNotice,

    @Schema(description = "국가 그룹. 표시 순서")
    List<ShippingZoneAdminItem> zones,

    @Schema(description = "낙관적 락 버전 — 교체(PUT) 요청의 version 에 그대로 보낸다", example = "3")
    long version,

    @Schema(description = "마지막 수정 시각 (UTC, ISO-8601)", example = "2026-10-18T03:00:00Z")
    Instant updatedAt
) {
}
