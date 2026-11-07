package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request.SizeChartDraftApplyRequest;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request.SizeChartDraftCreateRequest;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request.SizeChartDraftValidationMessage;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftAcceptedResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftApplyResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftSummaryResponse;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 사이즈표 초안", description = "텍스트 초안 생성과 검토 및 적용")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
public class SizeChartDraftAdminController {
    private static final String DRAFTS = "/api/v1/admin/size-chart-drafts";
    private final SizeChartDraftUseCase useCase;

    @Operation(summary = "사이즈표 초안 생성", description = "같은 운영자가 같은 UUID와 입력으로 재요청하면 기존 초안과 작업을 반환합니다. 작업 연결이 확인된 경우에만 접수합니다.")
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @AdminAudited(action = "SIZE_CHART_DRAFT_CREATE")
    @PostMapping("/api/v1/admin/products/{productId}/size-chart/drafts")
    public ResponseEntity<Response<SizeChartDraftAcceptedResponse>> create(
        @AuthenticationPrincipal MemberPrincipal actor,
        @Parameter(description = "상품 아이디") @PathVariable @Positive(message = SizeChartDraftValidationMessage.PRODUCT_ID) long productId,
        @Parameter(description = "요청별 UUID. 재시도에는 같은 값을 사용합니다", required = true)
        @RequestHeader("Idempotency-Key")
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", message = SizeChartDraftValidationMessage.KEY) String key,
        @RequestBody @Valid SizeChartDraftCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.createAdmin(request.toCommand(productId, actor.memberId(), UUID.fromString(key)))));
    }

    @Operation(summary = "사이즈표 초안 조회", description = "검토 상태와 구조화된 후보 치수 및 경고를 조회합니다.")
    @PreAuthorize(AdminAuthority.CATALOG_READ)
    @GetMapping(DRAFTS + "/{draftId}")
    public ResponseEntity<Response<SizeChartDraftResponse>> get(
        @Parameter(description = "초안 아이디") @PathVariable @Positive(message = SizeChartDraftValidationMessage.DRAFT_ID) long draftId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.getAdmin(draftId)));
    }

    @Operation(summary = "사이즈표 초안 목록", description = "최신순으로 조회합니다. 내 요청만 보기에는 현재 운영자의 인증 정보를 사용합니다.")
    @PreAuthorize(AdminAuthority.CATALOG_READ)
    @GetMapping(DRAFTS)
    public ResponseEntity<Response<PageResponse<SizeChartDraftSummaryResponse>>> list(
        @AuthenticationPrincipal MemberPrincipal actor,
        @Parameter(description = "상품 아이디 필터") @RequestParam(required = false) @Positive(message = SizeChartDraftValidationMessage.PRODUCT_ID) Long productId,
        @Parameter(description = "현재 운영자가 요청한 초안만 조회") @RequestParam(defaultValue = "false") boolean requestedByMe,
        @Parameter(description = "0부터 센 페이지") @RequestParam(defaultValue = "0") @Min(value = 0, message = SizeChartDraftValidationMessage.PAGE) int page,
        @Parameter(description = "페이지 크기. 1~100") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = SizeChartDraftValidationMessage.SIZE) @Max(value = 100, message = SizeChartDraftValidationMessage.SIZE) int size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.listAdmin(productId, requestedByMe ? actor.memberId() : null, page, size)));
    }

    @Operation(summary = "사이즈표 초안 새로고침", description = "완료한 생성 작업의 결과를 검증하고 초안에 반영합니다.")
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @AdminAudited(action = "SIZE_CHART_DRAFT_REFRESH")
    @PostMapping(DRAFTS + "/{draftId}/refresh")
    public ResponseEntity<Response<SizeChartDraftResponse>> refresh(
        @AuthenticationPrincipal MemberPrincipal actor,
        @Parameter(description = "초안 아이디") @PathVariable @Positive(message = SizeChartDraftValidationMessage.DRAFT_ID) long draftId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.refreshAdmin(draftId, actor.memberId())));
    }

    @Operation(summary = "검토한 사이즈표 초안 적용", description = "누락 치수를 보완하고 경고를 확인한 사이즈표로 상품 사이즈표를 전체 교체합니다.")
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @AdminAudited(action = "SIZE_CHART_DRAFT_APPLY")
    @PostMapping(DRAFTS + "/{draftId}/apply")
    public ResponseEntity<Response<SizeChartDraftApplyResponse>> apply(
        @AuthenticationPrincipal MemberPrincipal actor,
        @Parameter(description = "초안 아이디") @PathVariable @Positive(message = SizeChartDraftValidationMessage.DRAFT_ID) long draftId,
        @RequestBody @Valid SizeChartDraftApplyRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(
            useCase.applyAdmin(draftId, request.expectedDraftVersion(), request.correctedChart().toCommand(), request.acknowledgedWarningIds(), actor.memberId())));
    }
}
