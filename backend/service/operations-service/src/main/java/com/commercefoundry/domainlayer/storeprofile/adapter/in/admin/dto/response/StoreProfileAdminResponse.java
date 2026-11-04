package com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record StoreProfileAdminResponse(
    @Schema(description = "쇼핑몰 이름 (최대 100자)")
    String storeName,

    @Schema(description = "상호 (최대 200자)")
    String companyName,

    @Schema(description = "대표자 (최대 100자)")
    String representativeName,

    @Schema(description = "ISO 3166-1 alpha-2 국가 코드 (최대 2자)")
    String countryCode,

    @Schema(description = "사업자 식별 번호 (최대 100자)")
    String businessRegistrationNumber,

    @Schema(description = "통신판매 신고 번호 (최대 100자)")
    String mailOrderRegistrationNumber,

    @Schema(description = "공개 사업장 주소 (최대 500자)")
    String address,

    @Schema(description = "고객센터 이메일 (최대 254자)")
    String customerServiceEmail,

    @Schema(description = "고객센터 전화 (최대 50자)")
    String customerServicePhone,

    @Schema(description = "고객센터 운영 시간 (최대 500자)")
    String customerServiceHours,

    @Schema(description = "낙관적 편집 버전")
    Long version
) {
}
