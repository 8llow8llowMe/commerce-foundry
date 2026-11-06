package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "운영자 사용 여부 변경 (#236)")
public record AdminStatusChangeRequest(

    @Schema(description = "사용 여부 — false 면 끄고(옛 로그인 즉시 끊김), true 면 다시 켠다(비밀번호 · OTP 는 끄기 전 그대로)", example = "false")
    @NotNull(message = MemberValidationMessage.ADMIN_ACCOUNT_ACTIVE_REQUIRED)
    Boolean active
) {
}
