package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.validation.AdminNormalizedEmailLength;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "가게 운영자 계정 추가. 역할은 받지 않는다 — 언제나 관리자로 만든다. 고객 이메일은 승격하지 않는다")
public record AdminAccountCreateRequest(

    @Schema(description = "로그인 이메일", example = "staff@example.com")
    @NotBlank(message = MemberValidationMessage.ADMIN_EMAIL_REQUIRED)
    @Size(max = Member.EMAIL_MAX_LENGTH, message = MemberValidationMessage.ADMIN_EMAIL_LENGTH_INVALID)
    @AdminNormalizedEmailLength(message = MemberValidationMessage.ADMIN_EMAIL_LENGTH_INVALID)
    @Email(regexp = AdminRequestPatterns.EMAIL, message = MemberValidationMessage.ADMIN_ACCOUNT_EMAIL_FORMAT)
    String email,

    @Schema(description = "비밀번호 — 12~64자. 영문 · 숫자를 1자 이상씩 포함하고 공백 없는 ASCII", example = "shop-staff-2026")
    @NotBlank(message = MemberValidationMessage.ADMIN_PASSWORD_REQUIRED)
    @Size(min = 12, max = 64, message = MemberValidationMessage.ADMIN_ACCOUNT_PASSWORD_LENGTH)
    @Pattern(regexp = AdminRequestPatterns.PASSWORD, message = MemberValidationMessage.ADMIN_ACCOUNT_PASSWORD_FORMAT)
    String password,

    @Schema(description = "이름 — 1~50자", example = "김운영")
    @NotBlank(message = MemberValidationMessage.ADMIN_ACCOUNT_NAME_REQUIRED)
    @Size(max = Member.NAME_MAX_LENGTH, message = MemberValidationMessage.ADMIN_ACCOUNT_NAME_LENGTH)
    String name
) {
}
