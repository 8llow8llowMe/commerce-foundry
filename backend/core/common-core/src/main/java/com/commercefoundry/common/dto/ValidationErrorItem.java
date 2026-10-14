package com.commercefoundry.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "필드 단위 검증 오류 항목")
public record ValidationErrorItem(

    @Schema(description = "오류 코드 (도메인별 검증 코드)", example = "MEMBER_103")
    String code,

    @Schema(description = "오류가 발생한 필드명", example = "phone")
    String field,

    @Schema(description = "오류 메시지", example = "휴대폰 번호 형식이 올바르지 않습니다.")
    String message
) {

}
