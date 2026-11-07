package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.application.port.in.AdminPasswordResetAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 비밀번호 재설정", description = "운영자 비밀번호 재설정")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/admins")
public class AdminPasswordResetAdminController {
    private final AdminPasswordResetAdminUseCase adminPasswordResetAdminUseCase;

    @Operation(summary = "비밀번호 재설정 링크 발송", description = "활성 대표 관리자가 요청한다. 대상 없음·고객·비활성·메일 발송 장애는 같은 성공 응답이다.")
    @PreAuthorize(AdminAuthority.STAFF_MANAGE)
    @AdminAudited(action = "ADMIN_PASSWORD_RESET_LINK_SEND")
    @PostMapping("/{memberId}/password-reset-link")
    public ResponseEntity<Response<Void>> send(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "운영자 아이디")
        @PathVariable @Positive(message = MemberValidationMessage.ADMIN_ACCOUNT_ID_POSITIVE) long memberId
    ) {
        adminPasswordResetAdminUseCase.send(admin.memberId(), memberId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success());
    }
}
