package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "진행 중인 OTP 등록 확인. 인증 앱에 비밀을 넣은 뒤 보이는 6자리 코드로 확인한다")
public record AdminOtpConfirmRequest(

    @Schema(description = "현재 비밀번호 — access 토큰만으로 OTP 를 걸지 못하게 다시 확인한다", example = "current-password1")
    @NotBlank(message = MemberValidationMessage.ADMIN_ACCOUNT_CURRENT_PASSWORD_REQUIRED)
    String currentPassword,

    @Schema(description = "인증 앱의 6자리 코드. 앞뒤 공백은 지운다", example = "123456")
    @NotBlank(message = MemberValidationMessage.ADMIN_OTP_CODE_REQUIRED)
    @Size(max = AdminRequestPatterns.OTP_CODE_MAX_LENGTH, message = MemberValidationMessage.ADMIN_OTP_TOTP_FORMAT)
    @Pattern(regexp = AdminRequestPatterns.OTP_TOTP, message = MemberValidationMessage.ADMIN_OTP_TOTP_FORMAT)
    String code
) {

    /** 비밀번호 · 코드를 가린다. */
    @Override
    public String toString() {
        return "AdminOtpConfirmRequest[currentPassword=****, code=****]";
    }
}
