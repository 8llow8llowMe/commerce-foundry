package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.SignUpRequest;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SignUpResponse;
import com.commercefoundry.domainlayer.member.application.port.in.MemberWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원가입 · 로그인 · 토큰(#4 A3 · A4). 본문 검증은 {@code @Valid} 가 하고 필드별 코드는 {@code MemberValidationMessage} 다.
 */
@Tag(name = "인증", description = "회원가입 · 로그인 · 토큰 재발급 · 로그아웃")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthWebController {

    private final MemberWebUseCase memberWebUseCase;

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
}
