package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "OTP 해제 · 복구 코드 재발급의 재인증 — 현재 비밀번호와 인증 앱 코드 또는 복구 코드 하나")
public record AdminOtpReauthenticationRequest(

    @Schema(description = "현재 비밀번호", example = "current-password1")
    @NotBlank(message = MemberValidationMessage.ADMIN_ACCOUNT_CURRENT_PASSWORD_REQUIRED)
    String currentPassword,

    @Schema(description = "인증 앱의 6자리 코드 또는 복구 코드(xxxxx-xxxxx, 소문자 · 하이픈 생략 가능). 쓴 코드는 다시 쓸 수 없다", example = "ABCDE-FGH23")
    @NotBlank(message = MemberValidationMessage.ADMIN_OTP_CODE_REQUIRED)
    @Size(max = AdminRequestPatterns.OTP_CODE_MAX_LENGTH, message = MemberValidationMessage.ADMIN_OTP_CODE_FORMAT)
    @Pattern(regexp = AdminRequestPatterns.OTP_CODE, message = MemberValidationMessage.ADMIN_OTP_CODE_FORMAT)
    String code
) {

    /** 비밀번호 · 코드를 가린다. */
    @Override
    public String toString() {
        return "AdminOtpReauthenticationRequest[currentPassword=****, code=****]";
    }
}
