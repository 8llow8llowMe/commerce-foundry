package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "추가한 가게 운영자 계정. 비밀번호는 내려주지 않는다")
public record AdminAccountCreatedResponse(
    @Schema(description = "관리자 회원 아이디", example = "4611686018427387904")
    String memberId
) {
}
