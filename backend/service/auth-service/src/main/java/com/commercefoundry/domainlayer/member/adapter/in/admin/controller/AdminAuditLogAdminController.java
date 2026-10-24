package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.application.command.AdminAuditLogSearchCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.application.model.AdminAuditAction;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuditLogAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 감사 로그 조회 (#192). 기록은 업무(관리자 로그인 · 회원 목록 조회)가 {@code AdminAuditPort} 로 남기고, 여기는 읽기만 한다 — 고치거나 지우는 API 가 없다.
 *
 * <p>파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 감사 로그", description = "관리자 로그인 · 회원 목록 조회 같은 관리자 행위 기록 조회")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/audit-logs")
public class AdminAuditLogAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminAuditLogAdminUseCase adminAuditLogAdminUseCase;

    @Operation(
        summary = "관리자 감사 로그 조회",
        description = "최신순(감사 로그 아이디 내림차순 — Snowflake 라 기록 순서와 같다) 페이지다. 행위 · 관리자로 거를 수 있고, 없으면 전체다. "
            + "page 는 0부터, size 는 1~100(기본 20)이고 벗어나면 400(AUTH_133 · AUTH_134), actorMemberId 가 양수가 아니면 400(AUTH_135), "
            + "행위 값이 틀리면 400(AUTH_198)이다. 로그인 실패는 행위자를 모르므로 actorMemberId 가 null 이고, 노린 관리자 계정이 target 에 남는다. "
            + "이 조회 자체는 감사 로그에 남지 않는다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<AdminAuditLogAdminItem>>> searchAuditLogs(
        @Parameter(description = "행위 — ADMIN_LOGIN · MEMBER_SEARCH. 없으면 전체") @RequestParam(required = false) AdminAuditAction action,
        @Parameter(description = "행위한 관리자 회원 아이디. 없으면 전체") @RequestParam(required = false)
        @Positive(message = MemberValidationMessage.ADMIN_AUDIT_ACTOR_ID_POSITIVE) Long actorMemberId,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = MemberValidationMessage.ADMIN_PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = MemberValidationMessage.ADMIN_SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = MemberValidationMessage.ADMIN_SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(
            adminAuditLogAdminUseCase.searchAuditLogs(new AdminAuditLogSearchCommand(action, actorMemberId, page, size))
        ));
    }
}
