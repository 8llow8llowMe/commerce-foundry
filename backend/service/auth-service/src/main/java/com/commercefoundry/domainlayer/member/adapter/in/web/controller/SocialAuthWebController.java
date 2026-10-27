package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.SocialLoginRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SocialAuthorizationResponse;
import com.commercefoundry.domainlayer.member.application.port.in.SocialAuthWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "소셜 인증", description = "카카오 · 네이버 고객 로그인")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/oauth/{provider}")
public class SocialAuthWebController {
    private final SocialAuthWebUseCase useCase;

    @Operation(summary = "소셜 인가 URL 발급", description = "state와 verifier를 같은 탭 sessionStorage에 저장한다. verifier는 URL에 넣지 않는다.")
    @PreAuthorize("permitAll()")
    @GetMapping("/authorize-url")
    public ResponseEntity<Response<SocialAuthorizationResponse>> authorize(
        @Parameter(description = "kakao 또는 naver", required = true) @PathVariable String provider
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.authorize(provider)));
    }

    @Operation(summary = "소셜 로그인", description = "콜백 state를 저장한 값과 비교한 뒤 code · state · verifier를 보낸다. 신규 가입은 필수 약관 동의가 필요하다. "
        + "state 소비 후 실패하면 인가 URL부터 다시 시작한다. 이메일이 같은 기존 계정과 연결하지 않으며 409로 거절한다.")
    @PreAuthorize("permitAll()")
    @PostMapping("/login")
    public ResponseEntity<Response<AuthTokenResponse>> login(
        @Parameter(description = "kakao 또는 naver", required = true) @PathVariable String provider,
        @Valid @RequestBody SocialLoginRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.login(provider, request.toCommand())));
    }
}