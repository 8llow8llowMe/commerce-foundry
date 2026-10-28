package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.*;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.*;
import com.commercefoundry.domainlayer.member.application.port.in.SocialAccountWebUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "소셜 계정 연결", description = "일반 계정의 카카오 · 네이버 연결")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/social-accounts")
public class SocialAccountWebController {
    private final SocialAccountWebUseCase useCase;

    @Operation(summary = "연결된 소셜 제공자 조회")
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<Response<SocialAccountsResponse>> list(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.list(member.memberId())));
    }

    @Operation(summary = "비밀번호 재인증 후 연결 인가 URL 발급", description = "로그인용과 별도 목적의 state · verifier를 같은 탭에 보관한다.")
    @PostMapping("/{provider}/authorize-url")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<Response<SocialAuthorizationResponse>> authorize(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "kakao 또는 naver", required = true) @PathVariable String provider,
        @Valid @RequestBody SocialAccountAuthorizationRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.authorize(member.memberId(), provider, request.currentPassword())));
    }

    @Operation(summary = "기존 계정에 소셜 식별자 연결", description = "이미 같은 계정에 연결됐으면 성공한다. 토큰을 발급하지 않는다.")
    @PostMapping("/{provider}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<Response<SocialAccountsResponse>> link(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "kakao 또는 naver", required = true) @PathVariable String provider,
        @Valid @RequestBody SocialAccountLinkRequest request
    ) {
        var response = useCase.link(member.memberId(), provider, request.code(), request.state(), request.verifier());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(response));
    }
}
