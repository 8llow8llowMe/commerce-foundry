package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "배송지 목록 항목")
public record ShippingAddressItem(

    @Schema(description = "배송지 아이디", example = "4611686018427387904")
    String addressId,

    @Schema(description = "별칭. 없으면 null", example = "집", nullable = true)
    String label,

    @Schema(description = "받는 사람 이름", example = "홍길동")
    String recipientName,

    @Schema(description = "받는 사람 연락처 — 하이픈 없이", example = "01012345678")
    String recipientPhone,

    @Schema(description = "국가 코드 (ISO 3166-1 alpha-2)", example = "KR")
    String countryCode,

    @Schema(description = "우편번호. 해외 주소는 없으면 null", example = "06236", nullable = true)
    String postalCode,

    @Schema(description = "주소", example = "서울특별시 강남구 테헤란로 123")
    String addressLine1,

    @Schema(description = "상세 주소. 없으면 null", example = "4층 401호", nullable = true)
    String addressLine2,

    @Schema(description = "도시. 국내 주소는 없으면 null", example = "Seoul", nullable = true)
    String city,

    @Schema(description = "주 · 도. 없으면 null", example = "California", nullable = true)
    String stateProvince,

    @Schema(description = "기본 배송지인가. 주소가 있으면 기본은 정확히 하나다", example = "true")
    boolean isDefault
) {

    /** 개인정보(받는 사람 · 연락처 · 주소)를 싣지 않는다. */
    @Override
    public String toString() {
        return "ShippingAddressItem[addressId=" + addressId + ", countryCode=" + countryCode + ", isDefault=" + isDefault + "]";
    }
}
