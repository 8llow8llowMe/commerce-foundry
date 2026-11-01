package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** 어드민 주문 상세의 배송지 (#17) — 받는 분 · 연락처 · 상세 주소 · 요청 사항은 마스킹한 값만 내린다. */
@Builder
@Schema(description = "배송지 (주문 시점, 마스킹)")
public record OrderAdminShippingAddressItem(

    @Schema(description = "받는 분 (마스킹)", example = "홍*동")
    String recipientName,

    @Schema(description = "연락처 (마스킹)", example = "010****5678")
    String recipientPhone,

    @Schema(description = "배송 국가 (ISO 3166-1 alpha-2)", example = "KR")
    String countryCode,

    @Schema(description = "우편번호", example = "63100", nullable = true)
    String postalCode,

    @Schema(description = "기본 주소", example = "제주특별자치도 제주시 첨단로 242")
    String addressLine1,

    @Schema(description = "상세 주소 — 있으면 ****", example = "****", nullable = true)
    String addressLine2,

    @Schema(description = "도시 (해외)", nullable = true)
    String city,

    @Schema(description = "주 · 도 (해외)", nullable = true)
    String stateProvince,

    @Schema(description = "배송 요청 사항 — 있으면 **** (공동현관 비밀번호 같은 자유 입력이 들어간다)", example = "****", nullable = true)
    String deliveryMemo
) {
}
