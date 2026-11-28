package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.request.LegalDraftGenerationRequest;
import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.response.LegalDraftGenerationResponse;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.command.LegalDraftRegisterCommand;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.in.LegalDraftGenerationAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 법률 문서 AI 초안", description = "시작 템플릿을 이용한 검토용 후보 생성")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/store/legal-document-drafts/generations")
public class LegalDraftGenerationAdminController {
    private final LegalDraftGenerationAdminUseCase useCase;

    @Operation(summary = "검토용 생성 작업 접수", description = "Idempotency-Key는 16~80자의 영문·숫자·하이픈·밑줄입니다.")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "LEGAL_DRAFT_GENERATION_REGISTER")
    @PostMapping
    public ResponseEntity<Response<LegalDraftGenerationResponse.Accepted>> register(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "같은 요청을 다시 보낼 때 쓰는 요청 키 (16~80자)") @RequestHeader("Idempotency-Key") String requestKey,
        @RequestBody LegalDraftGenerationRequest request
    ) {
        var command = new LegalDraftRegisterCommand(member.memberId(), requestKey, request.type(), request.locale(), request.templateVersion(),
            request.confirmedFacts());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Response.success(useCase.register(command)));
    }

    @Operation(summary = "내 생성 작업 상태와 검토용 후보 조회")
    @PreAuthorize(AdminAuthority.SETTINGS_READ)
    @GetMapping("/{jobId}")
    public ResponseEntity<Response<LegalDraftGenerationResponse>> get(
        @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "작업 아이디") @PathVariable long jobId
    ) {
        return ok(useCase.get(member.memberId(), jobId));
    }

    @Operation(summary = "검토 확인 기록", description = "최초 검토 확인 시각을 기록합니다. 사용하려면 법률 문서 초안 저장 API에서 별도 저장해 주세요.")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "LEGAL_DRAFT_GENERATION_REVIEW")
    @PostMapping("/{jobId}/review")
    public ResponseEntity<Response<LegalDraftGenerationResponse>> review(
        @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "작업 아이디") @PathVariable long jobId
    ) {
        return ok(useCase.review(member.memberId(), jobId));
    }

    @Operation(summary = "내 생성 작업 취소 요청")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "LEGAL_DRAFT_GENERATION_CANCEL")
    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<Response<LegalDraftGenerationResponse>> cancel(
        @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "작업 아이디") @PathVariable long jobId
    ) {
        return ok(useCase.cancel(member.memberId(), jobId));
    }

    private ResponseEntity<Response<LegalDraftGenerationResponse>> ok(LegalDraftGenerationResponse data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(data));
    }
}
