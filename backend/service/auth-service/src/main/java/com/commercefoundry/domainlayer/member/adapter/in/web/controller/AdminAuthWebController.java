package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.AdminLoginRequest;
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
 * 관리자 로그인 (#6). 어드민 진입({@code adapter/in/admin}, {@code /api/v1/admin/**})이 아니라 여기 둔다 — 그 경로는 게이트웨이 · 서비스 URL 규칙이
 * ADMIN 토큰을 요구해 토큰을 받으러 오는 익명 요청이 닿지 못한다. 경로는 인증 경로 아래 {@code /api/v1/auth/admin/login} 이고 DTO 는 고객 로그인과 공유하지 않는다.
 *
 * <p>응답은 고객 로그인과 같이 {@code Cache-Control: no-store} 다 — 프록시 · 브라우저 캐시에 토큰이 남지 않게 (#4 결정 1).
 */
@Tag(name = "관리자 인증", description = "관리자 로그인 — 로그아웃은 고객과 같은 /api/v1/auth/logout 을 refresh 토큰 없이 부른다")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthWebController {

    private final AdminAuthWebUseCase adminAuthWebUseCase;

    @Operation(
        summary = "관리자 로그인",
        description = "관리자 이메일 · 비밀번호로 로그인하고 access 토큰(role=ADMIN)을 받는다. refresh 토큰은 주지 않는다 — "
            + "세션은 access 수명(기본 30분)까지이고 만료되면 다시 로그인한다. 이메일 없음 · 비밀번호 틀림 · 고객 계정은 모두 400(AUTH_030) 같은 문구다. "
            + "필드 검증은 AUTH_130~132 다. 같은 이메일의 관리자 로그인 시도가 창(기본 15분)의 허용 횟수(기본 5회)를 넘으면 비밀번호가 맞아도 "
            + "429(AUTH_040) + Retry-After(초)다 — 고객 로그인과 따로 센다. 시도 계수 저장소 장애는 503(AUTH_013)이다. 로그인 없이 호출한다."
    )
    @PreAuthorize("permitAll()")
    @PostMapping("/login")
    public ResponseEntity<Response<AdminLoginResponse>> login(@Valid @RequestBody AdminLoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(adminAuthWebUseCase.login(request.toCommand())));
    }
}
