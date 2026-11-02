package com.commercefoundry.domainlayer.knowledge.adapter.in.admin.controller;

import static com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeValidationMessage.*;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.dto.request.KnowledgeAdminRequests;
import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.dto.response.KnowledgeAdminResponses;
import com.commercefoundry.domainlayer.knowledge.application.port.in.KnowledgeAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 상담 지식 관리", description = "FAQ · 정책 · 응답 템플릿의 초안 · 승인 · 게시 관리")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/support/knowledge")
public class KnowledgeAdminController {

    private final KnowledgeAdminUseCase useCase;

    @Operation(summary = "지식 목록 조회", description = "최신 리비전 요약을 문서 아이디 내림차순으로 반환한다. 본문은 상세에서 조회한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<KnowledgeAdminResponses.Summary>>> list(
        @ParameterObject @Valid @ModelAttribute KnowledgeAdminRequests.ListQuery request
    ) {
        return ResponseEntity.ok(Response.success(useCase.list(request.toQuery())));
    }

    @Operation(summary = "지식 상세 조회", description = "문서 포인터와 최신 리비전 내용 · 승인 정보 · 낙관적 잠금 버전을 반환한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{documentId}")
    public ResponseEntity<Response<KnowledgeAdminResponses.Detail>> get(
        @Parameter(description = "문서 아이디") @PathVariable @Positive(message = DOCUMENT_ID) long documentId
    ) {
        return ResponseEntity.ok(Response.success(useCase.get(documentId)));
    }

    @Operation(summary = "지식 문서 생성", description = "문서와 첫 초안을 생성한다. 요청 재시도의 멱등성은 보장하지 않는다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "KNOWLEDGE_CREATE")
    @PostMapping
    public ResponseEntity<Response<KnowledgeAdminResponses.Detail>> create(
        @AuthenticationPrincipal MemberPrincipal admin,
        @RequestBody @Valid KnowledgeAdminRequests.Create request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(
            useCase.create(request.toCommand(), admin.memberId())));
    }

    @Operation(summary = "최신 초안 전체 편집", description = "최신 DRAFT만 편집한다. 리비전 버전 충돌은 409다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "KNOWLEDGE_EDIT")
    @PutMapping("/{documentId}")
    public ResponseEntity<Response<KnowledgeAdminResponses.Detail>> edit(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "문서 아이디") @PathVariable @Positive(message = DOCUMENT_ID) long documentId,
        @RequestBody @Valid KnowledgeAdminRequests.Edit request
    ) {
        return ResponseEntity.ok(Response.success(useCase.edit(documentId, request.revisionNumber(), request.expectedVersion(),
            request.content().toCommand(), admin.memberId())));
    }

    @Operation(summary = "새 초안 생성", description = "기존 게시본은 유지한다. 이전 문서 버전으로 재시도하면 409이므로 재조회해야 한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "KNOWLEDGE_DRAFT_CREATE")
    @PostMapping("/{documentId}/revisions")
    public ResponseEntity<Response<KnowledgeAdminResponses.Detail>> createDraft(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "문서 아이디") @PathVariable @Positive(message = DOCUMENT_ID) long documentId,
        @RequestBody @Valid KnowledgeAdminRequests.Draft request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(
            useCase.createDraft(documentId, request.expectedDocumentVersion(), request.content().toCommand(), admin.memberId())));
    }

    @Operation(summary = "최신 초안 승인", description = "인증된 관리자를 승인자로 기록한다. 승인 후 내용은 새 초안으로만 변경한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "KNOWLEDGE_APPROVE")
    @PostMapping("/{documentId}/approve")
    public ResponseEntity<Response<KnowledgeAdminResponses.Detail>> approve(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "문서 아이디") @PathVariable @Positive(message = DOCUMENT_ID) long documentId,
        @RequestBody @Valid KnowledgeAdminRequests.Approve request
    ) {
        return ResponseEntity.ok(Response.success(useCase.approve(documentId, request.revisionNumber(), request.expectedVersion(), admin.memberId())));
    }

    @Operation(summary = "최신 승인본 게시", description = "문서 · 리비전 버전을 모두 검사한다. 현재 게시본의 동일 요청은 버전 검사 전에 성공한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "KNOWLEDGE_PUBLISH")
    @PostMapping("/{documentId}/publish")
    public ResponseEntity<Response<KnowledgeAdminResponses.Detail>> publish(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "문서 아이디") @PathVariable @Positive(message = DOCUMENT_ID) long documentId,
        @RequestBody @Valid KnowledgeAdminRequests.Publish request
    ) {
        return ResponseEntity.ok(Response.success(useCase.publish(documentId, request.revisionNumber(), request.expectedVersion(),
            request.expectedDocumentVersion(), admin.memberId())));
    }

    @Operation(summary = "지식 리비전 보관", description = "물리 삭제 없이 ARCHIVED로 바꾼다. 현재 게시본이면 게시 포인터를 지운다. 보관된 대상의 재요청은 성공한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "KNOWLEDGE_ARCHIVE")
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Response<Void>> archive(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "문서 아이디") @PathVariable @Positive(message = DOCUMENT_ID) long documentId,
        @Parameter(description = "보관할 리비전 번호") @RequestParam @Positive(message = REVISION) int revisionNumber,
        @Parameter(description = "조회한 리비전 버전") @RequestParam @PositiveOrZero(message = VERSION) Long expectedVersion,
        @Parameter(description = "조회한 문서 버전") @RequestParam @PositiveOrZero(message = DOCUMENT_VERSION) Long expectedDocumentVersion
    ) {
        useCase.archive(documentId, revisionNumber, expectedVersion, expectedDocumentVersion, admin.memberId());
        return ResponseEntity.ok(Response.success());
    }
}
