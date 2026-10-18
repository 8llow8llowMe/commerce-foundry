package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.LoginRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.LogoutRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.SignUpRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.TokenReissueRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SignUpResponse;
import com.commercefoundry.domainlayer.member.application.port.in.AuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.port.in.MemberWebUseCase;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원가입 · 로그인 · 토큰(#4 A3 · A4). 본문 검증은 {@code @Valid} 가 하고 필드별 코드는 {@code MemberValidationMessage} 다.
 *
 * <p>토큰을 담은 응답(로그인 · 재발급)은 {@code Cache-Control: no-store} 다 — 프록시 · 브라우저 캐시에 토큰이 남지 않게 (#4 결정 1).
 */
@Tag(name = "인증", description = "회원가입 · 로그인 · 토큰 재발급 · 로그아웃")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthWebController {

    private final MemberWebUseCase memberWebUseCase;
    private final AuthWebUseCase authWebUseCase;

    @Operation(
        summary = "회원가입",
        description = "고객(CUSTOMER)으로 가입한다. 이용약관 · 개인정보 동의가 true 가 아니면 400(AUTH_111 · AUTH_112), 이미 가입된 이메일이면 409(AUTH_002)다. "
            + "동의 3종은 서버가 정한 약관 버전과 함께 이력으로 남는다. 토큰은 주지 않는다 — 로그인을 따로 한다. 로그인 없이 호출한다."
    )
    @PreAuthorize("permitAll()")
    @PostMapping("/sign-up")
    public ResponseEntity<Response<SignUpResponse>> signUp(@Valid @RequestBody SignUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(memberWebUseCase.signUp(request.toCommand())));
    }

    @Operation(
        summary = "로그인",
        description = "고객 이메일 · 비밀번호로 로그인하고 access · refresh 토큰을 받는다. 이메일 없음 · 비밀번호 틀림 · 관리자 계정은 모두 400(AUTH_001) 같은 문구다 — "
            + "401 은 토큰 거절에만 쓴다. 토큰 저장소 장애는 503(AUTH_013)이다. 로그인 없이 호출한다."
    )
    @PreAuthorize("permitAll()")
    @PostMapping("/login")
    public ResponseEntity<Response<AuthTokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(authWebUseCase.login(request.toCommand())));
    }

    @Operation(
        summary = "토큰 재발급",
        description = "refresh 토큰을 회전해 새 access · refresh 토큰을 받는다 — 보낸 refresh 토큰은 이제 쓰지 않는다. "
            + "refresh 토큰 없음 · 형식 오류 401(AUTH_010), 만료 · 로그아웃 · 폐기 401(AUTH_011), 이미 쓴 토큰의 재사용 401(AUTH_012 — 그 로그인의 토큰을 모두 폐기한다)이다. "
            + "같은 토큰을 30초 안에 다시 보내면(동시 요청 · 재시도) 새 토큰을 더 준다(토큰당 3개까지, 넘으면 AUTH_012). "
            + "토큰 저장소 장애는 503(AUTH_013) — 토큰을 버리지 말고 다시 시도한다. access 토큰 없이 호출한다."
    )
    @PreAuthorize("permitAll()")
    @PostMapping("/token/reissue")
    public ResponseEntity<Response<AuthTokenResponse>> reissue(@RequestBody TokenReissueRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(authWebUseCase.reissue(request.refreshToken())));
    }

    @Operation(
        summary = "로그아웃",
        description = "보낸 refresh 토큰의 로그인(family)을 폐기하고, 이 access 토큰을 게이트웨이 차단 목록에 넣는다. "
            + "본문이 없거나 · refresh 토큰이 없거나 · 모르는 값이거나 · 다른 회원의 것이어도 access 토큰은 막고 200 이다(멱등 — 남의 로그인은 건드리지 않는다). "
            + "토큰 저장소 장애는 503(AUTH_013)이다."
    )
    @SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout")
    public ResponseEntity<Response<Void>> logout(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @RequestBody(required = false) LogoutRequest request
    ) {
        authWebUseCase.logout(member.memberId(), member.tokenId(), request == null ? null : request.refreshToken());
        return ResponseEntity.ok().body(Response.success());
    }
}
