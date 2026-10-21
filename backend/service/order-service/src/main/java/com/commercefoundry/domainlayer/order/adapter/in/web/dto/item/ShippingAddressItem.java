package com.commercefoundry.domainlayer.order.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 주문 배송지. 문자열은 받은 원문(앞뒤 공백만 자름) 그대로다 — HTML 이 아니므로 화면 · 메일 · CSV 는 텍스트로 이스케이프한다.
 */
@Builder
@Schema(description = "주문 시점의 배송지")
public record ShippingAddressItem(

    @Schema(description = "받는 사람", example = "홍길동")
    String recipientName,

    @Schema(description = "받는 사람 연락처", example = "010-1234-5678")
    String recipientPhone,

    @Schema(description = "국가 (ISO 3166-1 alpha-2)", example = "KR")
    String countryCode,

    @Schema(description = "우편번호. 해외 배송지에 없으면 null", example = "63100", nullable = true)
    String postalCode,

    @Schema(description = "기본 주소", example = "제주특별자치도 제주시 첨단로 242")
    String addressLine1,

    @Schema(description = "상세 주소. 없으면 null", example = "101동 1001호", nullable = true)
    String addressLine2,

    @Schema(description = "배송 요청 사항. 없으면 null", example = "문 앞에 놓아 주세요", nullable = true)
    String deliveryMemo
) {
}
