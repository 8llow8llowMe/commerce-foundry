package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.MemberProfileUpdateRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.MemberProfileResponse;
import com.commercefoundry.domainlayer.member.application.port.in.MemberWebUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 정보. 회원은 access 토큰의 memberId 로만 정한다 — 경로 · 본문으로 아이디를 받지 않는다 ({@code api-design-guide.md} §5).
 */
@Tag(name = "회원", description = "내 정보 조회 · 수정")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me")
public class MemberWebController {

    private final MemberWebUseCase memberWebUseCase;

    @Operation(summary = "내 정보 조회", description = "토큰이 없으면 401(SECURITY_001), 토큰은 유효하지만 회원이 없으면 404(AUTH_003)다.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<Response<MemberProfileResponse>> getMyProfile(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().body(Response.success(memberWebUseCase.getMyProfile(member.memberId())));
    }

    @Operation(
        summary = "내 정보 수정",
        description = "보낸 칸만 바꾸고 바뀐 내 정보를 돌려준다. 하나도 보내지 않으면 400(AUTH_115), 휴대폰 번호를 빈 문자열로 보내면 지운다. "
            + "마케팅 동의가 바뀌면 동의 이력을 한 줄 남긴다. 다른 요청이 먼저 고쳤으면 409(AUTH_004) — 다시 조회한 뒤 보낸다. 이메일 · 비밀번호는 여기서 바꾸지 않는다."
    )
    @PreAuthorize("isAuthenticated()")
    @PatchMapping
    public ResponseEntity<Response<MemberProfileResponse>> updateMyProfile(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @Valid @RequestBody MemberProfileUpdateRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(memberWebUseCase.updateMyProfile(member.memberId(), request.toCommand())));
    }
}
