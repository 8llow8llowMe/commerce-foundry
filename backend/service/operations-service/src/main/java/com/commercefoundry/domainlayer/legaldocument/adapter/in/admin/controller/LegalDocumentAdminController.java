package com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.dto.request.LegalDocumentAdminRequests;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.dto.response.LegalDocumentAdminResponses;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentValidationMessage;
import com.commercefoundry.domainlayer.legaldocument.application.port.in.LegalDocumentAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 법적 문서", description = "약관·개인정보처리방침의 불변 초안과 명시적 게시")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/store")
public class LegalDocumentAdminController {
    private final LegalDocumentAdminUseCase useCase;

    @Operation(summary = "법적 문서 목록", description = "등록한 문서의 버전과 포인터만 조회합니다. 본문은 포함하지 않습니다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/legal-documents")
    public ResponseEntity<Response<List<LegalDocumentAdminResponses.Summary>>> list() {
        return ok(useCase.list());
    }

    @Operation(summary = "법적 문서 최신 초안 조회")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/legal-documents/{type}/{locale}")
    public ResponseEntity<Response<LegalDocumentAdminResponses.Detail>> detail(
        @Parameter(description = "문서 유형 제어 코드", schema = @Schema(allowableValues = {"TERMS", "PRIVACY"})) @PathVariable String type,
        @Parameter(description = "언어 제어 코드", schema = @Schema(allowableValues = {"ko", "en"})) @PathVariable String locale
    ) {
        return ok(useCase.detail(type, locale));
    }

    @Operation(summary = "새 초안 저장", description = "항상 다음 리비전을 추가하며 현재 게시본은 유지합니다. 최초 등록은 expectedVersion=null입니다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "LEGAL_DOCUMENT_DRAFT_SAVE")
    @PutMapping("/legal-documents/{type}/{locale}/draft")
    public ResponseEntity<Response<LegalDocumentAdminResponses.Detail>> save(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "문서 유형 제어 코드", schema = @Schema(allowableValues = {"TERMS", "PRIVACY"})) @PathVariable String type,
        @Parameter(description = "언어 제어 코드", schema = @Schema(allowableValues = {"ko", "en"})) @PathVariable String locale,
        @Valid @RequestBody LegalDocumentAdminRequests.Draft request
    ) {
        return ok(useCase.save(member.memberId(), request.toCommand(type, locale)));
    }

    @Operation(summary = "최신 초안 즉시 게시", description = "검토 확인과 미치환 표시 제거가 필요합니다. 현재 게시본의 재요청은 변경 없이 성공합니다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "LEGAL_DOCUMENT_PUBLISH")
    @PostMapping("/legal-documents/{type}/{locale}/publish")
    public ResponseEntity<Response<LegalDocumentAdminResponses.Detail>> publish(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "문서 유형 제어 코드", schema = @Schema(allowableValues = {"TERMS", "PRIVACY"})) @PathVariable String type,
        @Parameter(description = "언어 제어 코드", schema = @Schema(allowableValues = {"ko", "en"})) @PathVariable String locale,
        @Valid @RequestBody LegalDocumentAdminRequests.Publish request
    ) {
        return ok(useCase.publish(member.memberId(), request.toCommand(type, locale)));
    }

    @Operation(summary = "리비전 이력", description = "최신 번호순으로 조회합니다. 이전 내용 복원은 새 초안 저장 후 게시합니다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/legal-documents/{type}/{locale}/revisions")
    public ResponseEntity<Response<PageResponse<LegalDocumentAdminResponses.Revision>>> revisions(
        @Parameter(description = "문서 유형 제어 코드", schema = @Schema(allowableValues = {"TERMS", "PRIVACY"})) @PathVariable String type,
        @Parameter(description = "언어 제어 코드", schema = @Schema(allowableValues = {"ko", "en"})) @PathVariable String locale,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0")
        @Min(value = 0, message = LegalDocumentValidationMessage.PAGE) int page,
        @Parameter(description = "페이지 크기 (1~20)") @RequestParam(defaultValue = "10")
        @Min(value = 1, message = LegalDocumentValidationMessage.PAGE) @Max(value = 20, message = LegalDocumentValidationMessage.PAGE) int size
    ) {
        return ok(useCase.revisions(type, locale, page, size));
    }

    @Operation(summary = "법적 문서 시작 템플릿", description = "질문·빈칸 구조와 체크리스트입니다. 법적 적합성을 보장하지 않으며 저장·게시·AI 호출은 하지 않습니다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/legal-document-templates/{type}")
    public ResponseEntity<Response<LegalDocumentAdminResponses.Template>> template(
        @Parameter(description = "문서 유형 제어 코드", schema = @Schema(allowableValues = {"TERMS", "PRIVACY"})) @PathVariable String type,
        @Parameter(description = "언어 제어 코드", schema = @Schema(allowableValues = {"ko", "en"})) @RequestParam(defaultValue = "ko") String locale
    ) {
        return ok(useCase.template(type, locale));
    }

    private <T> ResponseEntity<Response<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(data));
    }
}
