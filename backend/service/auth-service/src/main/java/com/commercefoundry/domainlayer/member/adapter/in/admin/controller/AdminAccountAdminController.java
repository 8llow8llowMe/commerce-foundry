package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminAccountCreateRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminPasswordChangeRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAccountUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가게 운영자 계정 (#193). 상품을 파는 사업자의 직원 계정을 추가 · 끄고, 로그인한 본인 비밀번호를 바꾼다.
 * 개발자 콘솔이 아니다. 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Tag(name = "[어드민] 운영자 계정", description = "가게 운영자 추가 · 비활성화 · 본인 비밀번호 변경")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminAccountAdminController {

    private final AdminAccountUseCase adminAccountUseCase;

    @Operation(summary = "운영자 추가", description = "관리자 계정을 만든다. 이미 있는 이메일(고객 포함)은 409(AUTH_002)이고 승격하지 않는다. "
        + "비밀번호는 응답에 없다. 201 {memberId}.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/admins")
    public ResponseEntity<Response<AdminAccountCreatedResponse>> createAdmin(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Valid @RequestBody AdminAccountCreateRequest request
    ) {
        AdminAccountCreatedResponse created = adminAccountUseCase.createAdmin(
            admin.memberId(), request.email(), request.password(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(created));
    }

    @Operation(summary = "운영자 비활성화", description = "활성 관리자를 끈다. 마지막 한 명은 409(AUTH_031). 없거나 고객이거나 이미 꺼진 계정은 404(AUTH_033). "
        + "끈 계정의 기존 access 토큰은 게이트웨이에서 거절된다.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/admins/{memberId}")
    public ResponseEntity<Response<Void>> deactivateAdmin(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "끌 관리자 아이디") @PathVariable @Positive(message = MemberValidationMessage.ADMIN_ACCOUNT_ID_POSITIVE) long memberId
    ) {
        adminAccountUseCase.deactivateAdmin(admin.memberId(), memberId, admin.tokenId());
        return ResponseEntity.ok().body(Response.success());
    }

    @Operation(summary = "본인 비밀번호 변경", description = "로그인한 관리자 자신의 비밀번호만 바꾼다. 현재 비밀번호가 틀리면 400(AUTH_032). "
        + "바꾼 뒤 기존 access 토큰은 게이트웨이에서 거절되므로 다시 로그인한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/me/password")
    public ResponseEntity<Response<Void>> changePassword(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Valid @RequestBody AdminPasswordChangeRequest request
    ) {
        adminAccountUseCase.changePassword(admin.memberId(), admin.tokenId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok().body(Response.success());
    }
}
