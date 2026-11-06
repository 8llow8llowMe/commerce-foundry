package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.security.principal.MemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "운영자 역할 변경 (#236). 기존 ADMIN · CUSTOMER 는 줄 수 없다")
public record AdminRoleChangeRequest(

    @Schema(description = "새 역할 — OWNER · MANAGER · STAFF 중 하나", example = "MANAGER", allowableValues = {"OWNER", "MANAGER", "STAFF"})
    @NotNull(message = MemberValidationMessage.ADMIN_ACCOUNT_ROLE_INVALID)
    @Pattern(regexp = AdminRequestPatterns.ASSIGNABLE_ROLE, message = MemberValidationMessage.ADMIN_ACCOUNT_ROLE_INVALID)
    String role
) {

    /** 검증을 지나온 역할. */
    public MemberRole assignedRole() {
        return MemberRole.valueOf(role);
    }
}
