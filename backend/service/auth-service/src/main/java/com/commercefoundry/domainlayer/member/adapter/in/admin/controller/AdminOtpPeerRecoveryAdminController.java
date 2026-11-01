package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminOtpReauthenticationRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.application.port.in.AdminOtpPeerRecoveryUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 동료 관리자의 OTP 복구. 행위자는 JWT의 회원 ID이고 대상만 경로에서 받는다. */
@Tag(name = "[어드민] 동료 OTP 복구", description = "동료 관리자의 분실한 OTP를 새 자격과 복구 코드로 교체")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/admins")
public class AdminOtpPeerRecoveryAdminController {

    private final AdminOtpPeerRecoveryUseCase adminOtpPeerRecoveryUseCase;

    @Operation(summary = "동료 OTP 복구", description = "행위자의 현재 비밀번호와 OTP 또는 복구 코드로 재인증한 뒤 대상의 OTP 자격·복구 코드를 교체한다. "
        + "새 복구 코드 8개는 이 응답에서만 보여 준다. 대상에게 최소 두 코드를 안전하게 전달해 로그인과 본인 OTP 재등록에 사용한다. "
        + "본인 대상은 400(AUTH_045), 대상 미등록은 404(AUTH_036), 재인증 오류는 400(AUTH_032 · AUTH_035), 시도 초과는 429(AUTH_043).")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{memberId}/otp/recovery")
    public ResponseEntity<Response<AdminOtpRecoveryCodesResponse>> recover(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "복구할 동료 관리자 아이디")
        @PathVariable @Positive(message = MemberValidationMessage.ADMIN_ACCOUNT_ID_POSITIVE) long memberId,
        @Valid @RequestBody AdminOtpReauthenticationRequest request
    ) {
        AdminOtpRecoveryCodesResponse issued = adminOtpPeerRecoveryUseCase.recover(
            admin.memberId(), memberId, request.currentPassword(), request.code());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(issued));
    }
}
