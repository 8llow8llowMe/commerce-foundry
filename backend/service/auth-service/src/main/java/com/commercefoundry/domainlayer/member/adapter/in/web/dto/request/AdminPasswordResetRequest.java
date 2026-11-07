package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminPasswordResetRequest(
    @Schema(description = "메일 링크 프래그먼트의 일회용 재설정 토큰")
    @NotBlank(message = MemberValidationMessage.ADMIN_PASSWORD_RESET_TOKEN_REQUIRED)
    String token,

    @Schema(description = "새 비밀번호 — 10~64자, 공백 없는 ASCII 영문과 숫자 포함")
    @NotBlank(message = MemberValidationMessage.ADMIN_PASSWORD_REQUIRED)
    @Size(min = 10, max = 64, message = MemberValidationMessage.ADMIN_ACCOUNT_PASSWORD_LENGTH)
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[!-~]+$", message = MemberValidationMessage.ADMIN_ACCOUNT_PASSWORD_FORMAT)
    String newPassword
) {
    @Override
    public String toString() { return "AdminPasswordResetRequest[redacted]"; }
}
