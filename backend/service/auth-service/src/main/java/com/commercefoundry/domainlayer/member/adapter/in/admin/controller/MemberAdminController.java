package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminMemberItem;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.application.port.in.MemberAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberPrincipal;
import com.commercefoundry.security.principal.MemberRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 회원 관리 (#6). 파라미터 검증은 Spring MVC 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 회원", description = "회원 목록 조회")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members")
public class MemberAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MemberAdminUseCase memberAdminUseCase;

    @Operation(
        summary = "회원 목록 조회",
        description = "최신순(회원 아이디 내림차순 — Snowflake 라 가입 순서와 같다) 페이지다. 이메일 · 이름 · 휴대폰 번호는 마스킹한 값이다. "
            + "역할을 주지 않으면 고객 · 관리자 모두다. page 는 0부터, size 는 1~100(기본 20)이고 벗어나면 400(AUTH_133 · AUTH_134), "
            + "역할 값이 틀리면 400(AUTH_198)이다. 조회는 감사 로그에 남는다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<AdminMemberItem>>> searchMembers(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = MemberValidationMessage.ADMIN_PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = MemberValidationMessage.ADMIN_SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = MemberValidationMessage.ADMIN_SIZE_RANGE) int size,
        @Parameter(description = "역할 — CUSTOMER · ADMIN. 없으면 전체") @RequestParam(required = false) MemberRole role
    ) {
        return ResponseEntity.ok().body(Response.success(memberAdminUseCase.searchMembers(admin.memberId(), role, page, size)));
    }
}
