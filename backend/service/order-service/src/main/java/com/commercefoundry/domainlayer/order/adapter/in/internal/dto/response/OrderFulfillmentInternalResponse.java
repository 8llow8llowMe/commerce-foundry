package com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** 서비스 간 출고 · 배송 완료 반영 결과 (#18). 호출자는 delivery-service 하나다 — 송장에 회원 · 배송 국가를 스냅숏한다. */
@Builder
@Schema(description = "서비스 간 출고 · 배송 완료 반영 결과. 이미 반영된 뒤의 재요청도 같은 모양으로 지금 상태를 낸다")
public record OrderFulfillmentInternalResponse(

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "주문한 회원 아이디 — 고객 배송 조회의 소유 판정에 쓴다", example = "4611686018427387905")
    String memberId,

    @Schema(description = "반영 뒤 주문 상태 제어 코드 (SHIPPING · DELIVERED, 이미 그 뒤였으면 PURCHASE_CONFIRMED · RETURN_REQUESTED 등)", example = "SHIPPING")
    String status,

    @Schema(description = "배송지 국가 (주문 배송지 스냅숏, ISO 3166-1 alpha-2)", example = "KR")
    String countryCode
) {
}
