package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.AdminPasswordResetRequest;
import com.commercefoundry.domainlayer.member.application.port.in.AdminPasswordResetWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 비밀번호 재설정", description = "운영자 비밀번호 재설정")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/admin/password-reset")
public class AdminPasswordResetWebController {
    private final AdminPasswordResetWebUseCase adminPasswordResetWebUseCase;

    @Operation(summary = "운영자 비밀번호 재설정", description = "일회용 링크로 비밀번호를 바꾸고 기존 access 토큰을 차단한다. 만료·위조·재사용은 같은 AUTH_061 오류다.")
    @PreAuthorize("permitAll()")
    @PostMapping
    public ResponseEntity<Response<Void>> reset(
        @Valid @RequestBody AdminPasswordResetRequest request,
        @Parameter(hidden = true) @RequestHeader(name = "X-Client-Subject", required = false) String clientSubject
    ) {
        adminPasswordResetWebUseCase.reset(request.token(), request.newPassword(), clientSubject);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success());
    }
}
