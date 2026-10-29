package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminOtpConfirmRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminOtpReauthenticationRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpEnrollmentResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpStatusResponse;
import com.commercefoundry.domainlayer.member.application.port.in.AdminOtpUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 관리자 본인의 OTP (#238 PR-A2). 행위자는 access 토큰의 memberId 만 쓴다. 클래스에 {@code @Validated} 를 달지 않는다.
 *
 * <p>해제는 {@code DELETE} 대신 {@code POST …/deactivation} 이다 — 재인증 본문(비밀번호 · 코드)이 필요한데 {@code DELETE} 본문은 HTTP 의미가 정해져 있지 않아
 * 프록시 · 클라이언트가 버릴 수 있다. 동사 대신 명사 리소스로 두는 규칙({@code api-design-guide.md} §1-1, 주문 {@code cancellation})을 따른다.
 *
 * <p>비밀 · 복구 코드를 싣는 응답은 {@code Cache-Control: no-store} 다 — 프록시 · 브라우저 캐시에 남지 않게.
 */
@Tag(name = "[어드민] 내 OTP", description = "로그인한 관리자 본인의 OTP 등록 · 확인 · 해제 · 복구 코드 재발급")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/me/otp")
public class AdminOtpAdminController {

    private final AdminOtpUseCase adminOtpUseCase;

    @Operation(summary = "내 OTP 상태", description = "등록 여부 · 확인 시각 · 남은 복구 코드 수. 확인 전 진행 중인 등록은 enrolled=false 다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<AdminOtpStatusResponse>> getStatus(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin) {
        return ResponseEntity.ok().body(Response.success(adminOtpUseCase.getStatus(admin.memberId())));
    }

    @Operation(summary = "OTP 등록 시작", description = "새 비밀과 인증 앱 등록 URI(otpauth)를 이번 한 번만 내려준다. expiresAt(기본 15분)까지 확인해야 한다. "
        + "다시 부르면 이전 진행 중 등록은 버려진다. 이미 등록돼 있으면 409(AUTH_037) — 바꾸려면 해제부터 한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/enrollment")
    public ResponseEntity<Response<AdminOtpEnrollmentResponse>> startEnrollment(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(adminOtpUseCase.startEnrollment(admin.memberId())));
    }

    @Operation(summary = "OTP 등록 확인", description = "현재 비밀번호와 인증 앱의 6자리 코드로 진행 중인 등록을 확인한다. 201 {recoveryCodes} — 복구 코드 8개는 이번 한 번만 보여 준다. "
        + "진행 중인 등록이 없으면 400(AUTH_038), 비밀번호가 틀리면 400(AUTH_032), 코드가 틀리면 400(AUTH_035), 이미 등록돼 있으면 409(AUTH_037), "
        + "비활성 관리자면 404(AUTH_033), 같은 관리자의 다른 OTP 요청과 겹치면 409(AUTH_044, 잠시 뒤 재시도), 시도가 15분에 5회를 넘으면 429(AUTH_043) + Retry-After.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Response<AdminOtpRecoveryCodesResponse>> confirmEnrollment(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Valid @RequestBody AdminOtpConfirmRequest request
    ) {
        AdminOtpRecoveryCodesResponse issued = adminOtpUseCase.confirmEnrollment(admin.memberId(), request.currentPassword(), request.code());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(Response.success(issued));
    }

    @Operation(summary = "OTP 해제", description = "현재 비밀번호와 인증 앱 코드 또는 복구 코드로 OTP 와 남은 복구 코드를 지운다. 쓴 코드는 다시 쓸 수 없다. "
        + "미등록이면 404(AUTH_036), 비활성 관리자면 404(AUTH_033), 비밀번호가 틀리면 400(AUTH_032), 코드가 틀리거나 이미 쓴 코드면 400(AUTH_035), "
        + "같은 관리자의 다른 OTP 요청과 겹치면 409(AUTH_044, 잠시 뒤 재시도), 시도 초과 429(AUTH_043) + Retry-After.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/deactivation")
    public ResponseEntity<Response<Void>> disable(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Valid @RequestBody AdminOtpReauthenticationRequest request
    ) {
        adminOtpUseCase.disable(admin.memberId(), request.currentPassword(), request.code());
        return ResponseEntity.ok().body(Response.success());
    }

    @Operation(summary = "OTP 복구 코드 재발급", description = "현재 비밀번호와 인증 앱 코드 또는 복구 코드로 복구 코드 8개를 새로 발급한다. 이전 코드는 모두 무효이고 새 코드는 이번 한 번만 보여 준다. "
        + "오류는 해제와 같다(AUTH_036 · AUTH_033 · AUTH_032 · AUTH_035 · AUTH_044 · AUTH_043).")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/recovery-codes")
    public ResponseEntity<Response<AdminOtpRecoveryCodesResponse>> regenerateRecoveryCodes(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Valid @RequestBody AdminOtpReauthenticationRequest request
    ) {
        AdminOtpRecoveryCodesResponse issued = adminOtpUseCase.regenerateRecoveryCodes(admin.memberId(), request.currentPassword(), request.code());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(issued));
    }
}
