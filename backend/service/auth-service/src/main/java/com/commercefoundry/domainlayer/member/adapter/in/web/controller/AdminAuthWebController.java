package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.AdminLoginRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.AdminOtpLoginRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuthWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 로그인 (#6 · #238 PR-B). 어드민 진입({@code adapter/in/admin}, {@code /api/v1/admin/**})이 아니라 여기 둔다 — 그 경로는 게이트웨이 · 서비스 URL 규칙이
 * ADMIN 토큰을 요구해 토큰을 받으러 오는 익명 요청이 닿지 못한다. 경로는 인증 경로 아래 {@code /api/v1/auth/admin/login} · {@code …/login/otp} 이고
 * DTO 는 고객 로그인과 공유하지 않는다.
 *
 * <p>응답은 고객 로그인과 같이 {@code Cache-Control: no-store} 다 — 프록시 · 브라우저 캐시에 토큰 · 챌린지가 남지 않게 (#4 결정 1).
 */
@Tag(name = "관리자 인증", description = "관리자 로그인(비밀번호 → OTP 등록 관리자는 2단계) — 로그아웃은 고객과 같은 /api/v1/auth/logout 을 refresh 토큰 없이 부른다")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthWebController {

    private final AdminAuthWebUseCase adminAuthWebUseCase;

    @Operation(
        summary = "관리자 로그인",
        description = "관리자 이메일 · 비밀번호로 로그인한다. OTP 를 등록하지 않은 관리자는 access 토큰(role=ADMIN)을 받는다(otpRequired=false). "
            + "OTP 를 등록한 관리자는 토큰 없이 otpRequired=true · challengeToken · challengeExpiresIn(초, 기본 300)을 받고 "
            + "POST /api/v1/auth/admin/login/otp 로 2단계를 마친다. refresh 토큰은 주지 않는다 — "
            + "세션은 access 수명(기본 30분)까지이고 만료되면 다시 로그인한다. 이메일 없음 · 비밀번호 틀림 · 고객 계정은 모두 400(AUTH_030) 같은 문구다. "
            + "필드 검증은 AUTH_130~132 다. 같은 이메일의 관리자 로그인 시도가 창(기본 15분)의 허용 횟수(기본 5회)를 넘으면 비밀번호가 맞아도 "
            + "429(AUTH_040) + Retry-After(초)다 — 고객 로그인과 따로 세고, 고객 로그인과 달리 성공한 로그인도 창에서 지우지 않는다. "
            + "시도 계수 · 챌린지 저장소 장애는 503(AUTH_013)이다. 로그인 없이 호출한다."
    )
    @PreAuthorize("permitAll()")
    @PostMapping("/login")
    public ResponseEntity<Response<AdminLoginResponse>> login(@Valid @RequestBody AdminLoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(adminAuthWebUseCase.login(request.toCommand())));
    }

    @Operation(
        summary = "관리자 로그인 2단계 (OTP)",
        description = "1단계가 준 challengeToken 과 인증 앱의 6자리 코드 또는 복구 코드로 access 토큰(role=ADMIN, otpRequired=false)을 받는다. "
            + "챌린지 없음 · 만료 · 이미 씀 · 그 사이 계정 비활성화 · OTP 해제 · 비밀번호 변경은 400(AUTH_034)이고 1단계부터 다시 한다. "
            + "코드가 틀리거나 이미 쓴 코드(같은 30초 코드 · 쓴 복구 코드)면 400(AUTH_035)이고 같은 챌린지로 다시 시도할 수 있다. "
            + "같은 관리자의 2단계 시도가 창(기본 15분)의 허용 횟수(기본 5회)를 넘으면 코드가 맞아도 429(AUTH_043) + Retry-After(초)다 — "
            + "성공해도 창에서 지우지 않는다. 같은 관리자의 다른 OTP 요청과 겹쳐 409(AUTH_044)이면 챌린지가 이미 소비됐으므로 1단계부터 다시 한다. "
            + "필드 검증은 AUTH_153(challengeToken) · AUTH_147 · AUTH_149(code)다. 저장소 장애는 503(AUTH_013), OTP 비밀을 읽지 못하면 503(AUTH_039)이다. "
            + "로그인 없이 호출한다."
    )
    @PreAuthorize("permitAll()")
    @PostMapping("/login/otp")
    public ResponseEntity<Response<AdminLoginResponse>> loginWithOtp(@Valid @RequestBody AdminOtpLoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(adminAuthWebUseCase.loginWithOtp(request.toCommand())));
    }
}
