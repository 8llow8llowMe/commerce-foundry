package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "소셜 계정 연결 재인증")
public record SocialAccountAuthorizationRequest(
    @Schema(description = "현재 계정 비밀번호")
    @NotBlank(message = MemberValidationMessage.PASSWORD_REQUIRED)
    String currentPassword
) {
    @Override
    public String toString() { return "SocialAccountAuthorizationRequest[redacted]"; }
}
