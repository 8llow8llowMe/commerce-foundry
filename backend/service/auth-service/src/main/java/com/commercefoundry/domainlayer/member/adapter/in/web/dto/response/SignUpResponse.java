package com.commercefoundry.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "회원가입 응답. 토큰은 주지 않는다 — 로그인을 따로 한다")
public record SignUpResponse(

    @Schema(description = "회원 아이디", example = "4611686018427387904")
    String memberId
) {
}
