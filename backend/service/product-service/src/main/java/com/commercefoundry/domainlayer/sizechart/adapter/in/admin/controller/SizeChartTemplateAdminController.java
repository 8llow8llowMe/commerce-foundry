package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.SizeChartTemplateSummaryAdminItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request.SizeChartTemplateUpsertRequest;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartTemplateAdminResponse;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartValidationMessage;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 사이즈표 템플릿. {@code /api/v1/admin/**} 은 URL 규칙이 ADMIN 을 먼저 요구하고, 메서드의 {@code hasRole('ADMIN')} 이 이중으로 막는다.
 * 작성자 칸 · Idempotency-Key 는 없다. 파라미터 검증은 Spring MVC 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Tag(name = "[어드민] 사이즈표 템플릿", description = "상품 사이즈표에 붙이는 치수 항목 템플릿")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/size-chart-templates")
public class SizeChartTemplateAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SizeChartAdminUseCase sizeChartAdminUseCase;

    @Operation(summary = "사이즈표 템플릿 목록", description = "최신순(아이디 내림차순) 페이지다. 항목 본문은 없고 개수만 담는다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<SizeChartTemplateSummaryAdminItem>>> getTemplates(
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0")
        @PositiveOrZero(message = SizeChartValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = SizeChartValidationMessage.SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = SizeChartValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok(Response.success(sizeChartAdminUseCase.getTemplates(page, size)));
    }

    @Operation(summary = "사이즈표 템플릿 조회", description = "항목을 포함한다. 없으면 404(PRODUCT_059)다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{templateId}")
    public ResponseEntity<Response<SizeChartTemplateAdminResponse>> getTemplate(
        @Parameter(description = "템플릿 아이디") @PathVariable @Positive(message = SizeChartValidationMessage.TEMPLATE_ID_POSITIVE) long templateId
    ) {
        return ResponseEntity.ok(Response.success(sizeChartAdminUseCase.getTemplate(templateId)));
    }

    @Operation(
        summary = "사이즈표 템플릿 생성",
        description = "이름 · 저장 단위 · 항목을 만든다. 항목 이름 또는 순서 중복은 400(PRODUCT_060 · PRODUCT_061)이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Response<SizeChartTemplateAdminResponse>> createTemplate(@RequestBody @Valid SizeChartTemplateUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(sizeChartAdminUseCase.createTemplate(request.toCommand())));
    }

    @Operation(
        summary = "사이즈표 템플릿 수정 (전체 교체)",
        description = "없으면 404(PRODUCT_059). 상품 사이즈표가 이 템플릿을 쓰면 단위 변경과 항목 추가 · 삭제는 409(PRODUCT_062)이고, "
            + "기존 columnId 로 이름 · 설명 · 순서만 바꾼다. 아무도 안 쓰면 항목을 새 아이디로 다시 만든다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{templateId}")
    public ResponseEntity<Response<SizeChartTemplateAdminResponse>> updateTemplate(
        @Parameter(description = "템플릿 아이디") @PathVariable @Positive(message = SizeChartValidationMessage.TEMPLATE_ID_POSITIVE) long templateId,
        @RequestBody @Valid SizeChartTemplateUpsertRequest request
    ) {
        return ResponseEntity.ok(Response.success(sizeChartAdminUseCase.updateTemplate(templateId, request.toCommand())));
    }

    @Operation(summary = "사이즈표 템플릿 삭제", description = "항목까지 지운다. 없으면 404(PRODUCT_059), 상품 사이즈표가 쓰면 409(PRODUCT_063)다.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Response<Void>> deleteTemplate(
        @Parameter(description = "템플릿 아이디") @PathVariable @Positive(message = SizeChartValidationMessage.TEMPLATE_ID_POSITIVE) long templateId
    ) {
        sizeChartAdminUseCase.deleteTemplate(templateId);
        return ResponseEntity.ok(Response.success());
    }
}
