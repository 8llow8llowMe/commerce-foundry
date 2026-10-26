package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "로그인한 가게 운영자의 비밀번호 변경. 다른 관리자의 비밀번호는 여기서 바꾸지 않는다")
public record AdminPasswordChangeRequest(

    @Schema(description = "현재 비밀번호", example = "current-password1")
    @NotBlank(message = MemberValidationMessage.ADMIN_ACCOUNT_CURRENT_PASSWORD_REQUIRED)
    String currentPassword,

    @Schema(description = "새 비밀번호 — 12~64자. 영문 · 숫자를 1자 이상씩 포함하고 공백 없는 ASCII", example = "next-password-2026")
    @NotBlank(message = MemberValidationMessage.ADMIN_PASSWORD_REQUIRED)
    @Size(min = 12, max = 64, message = MemberValidationMessage.ADMIN_ACCOUNT_PASSWORD_LENGTH)
    @Pattern(regexp = AdminRequestPatterns.PASSWORD, message = MemberValidationMessage.ADMIN_ACCOUNT_PASSWORD_FORMAT)
    String newPassword
) {
}
