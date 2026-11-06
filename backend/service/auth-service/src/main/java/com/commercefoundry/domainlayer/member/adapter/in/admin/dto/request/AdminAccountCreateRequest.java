package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.validation.AdminNormalizedEmailLength;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "가게 운영자 계정 추가. 역할을 주지 않으면 기존 관리자(ADMIN)로 만든다. 고객 이메일은 승격하지 않는다")
public record AdminAccountCreateRequest(

    @Schema(description = "로그인 이메일", example = "staff@example.com")
    @NotBlank(message = MemberValidationMessage.ADMIN_EMAIL_REQUIRED)
    @Size(max = Member.EMAIL_MAX_LENGTH, message = MemberValidationMessage.ADMIN_EMAIL_LENGTH_INVALID)
    @AdminNormalizedEmailLength(message = MemberValidationMessage.ADMIN_EMAIL_LENGTH_INVALID)
    @Email(regexp = AdminRequestPatterns.EMAIL, message = MemberValidationMessage.ADMIN_ACCOUNT_EMAIL_FORMAT)
    String email,

    @Schema(description = "비밀번호 — 10~64자. 영문 · 숫자를 1자 이상씩 포함하고 공백 없는 ASCII", example = "shop-staff-2026")
    @NotBlank(message = MemberValidationMessage.ADMIN_PASSWORD_REQUIRED)
    @Size(min = 10, max = 64, message = MemberValidationMessage.ADMIN_ACCOUNT_PASSWORD_LENGTH)
    @Pattern(regexp = AdminRequestPatterns.PASSWORD, message = MemberValidationMessage.ADMIN_ACCOUNT_PASSWORD_FORMAT)
    String password,

    @Schema(description = "이름 — 1~50자", example = "김운영")
    @NotBlank(message = MemberValidationMessage.ADMIN_ACCOUNT_NAME_REQUIRED)
    @Size(max = Member.NAME_MAX_LENGTH, message = MemberValidationMessage.ADMIN_ACCOUNT_NAME_LENGTH)
    String name,

    @Schema(description = "역할 — OWNER · MANAGER · STAFF 중 하나(#236). 없으면 ADMIN. 역할 나누기가 꺼져 있으면 409(AUTH_049)",
        example = "MANAGER", nullable = true, allowableValues = {"OWNER", "MANAGER", "STAFF"})
    @Pattern(regexp = AdminRequestPatterns.ASSIGNABLE_ROLE, message = MemberValidationMessage.ADMIN_ACCOUNT_ROLE_INVALID)
    String role
) {

    /** 검증을 지나온 역할. 없으면 null(ADMIN 으로 만든다). */
    public MemberRole assignedRole() {
        return role == null ? null : MemberRole.valueOf(role);
    }
}
