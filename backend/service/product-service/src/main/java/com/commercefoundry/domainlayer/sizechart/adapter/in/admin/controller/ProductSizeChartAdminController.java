package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request.ProductSizeChartUpsertRequest;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.ProductSizeChartAdminResponse;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartValidationMessage;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartAdminUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 상품 사이즈표. 상품 상세 응답에 넣지 않고 부모당 0~1개인 선택 리소스로 둔다.
 * 숨긴 상품도 조회한다. 상품이 없으면 404(PRODUCT_064)이고, 상품은 있는데 표가 없으면 200 · dataBody null 이다.
 * 응답 값은 항상 저장 단위 그대로다.
 */
@Tag(name = "[어드민] 상품 사이즈표", description = "상품에 붙인 사이즈표 조회 · 저장")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/products/{productId}/size-chart")
public class ProductSizeChartAdminController {

    private final SizeChartAdminUseCase sizeChartAdminUseCase;

    @Operation(
        summary = "상품 사이즈표 조회",
        description = "숨긴 상품도 본다. 상품이 없으면 404(PRODUCT_064)다. 상품은 있고 표가 없으면 200 이고 dataBody 는 null 이다. 값은 저장 단위 그대로다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<ProductSizeChartAdminResponse>> getSizeChart(
        @Parameter(description = "상품 아이디") @PathVariable @Positive(message = SizeChartValidationMessage.PRODUCT_ID_POSITIVE) long productId
    ) {
        return ResponseEntity.ok(Response.success(sizeChartAdminUseCase.getProductSizeChart(productId)));
    }

    @Operation(
        summary = "상품 사이즈표 저장 (전체 교체)",
        description = "없으면 만들고 있으면 행과 칸을 다시 넣는다. 상품이 없으면 404(PRODUCT_064), 본문 템플릿이 없으면 400(PRODUCT_065), "
            + "항목이 빠지거나 겹치거나 템플릿에 없으면 400(PRODUCT_066~068), 사이즈 이름 · 순서 중복은 400(PRODUCT_069 · PRODUCT_070)이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<Response<ProductSizeChartAdminResponse>> replaceSizeChart(
        @Parameter(description = "상품 아이디") @PathVariable @Positive(message = SizeChartValidationMessage.PRODUCT_ID_POSITIVE) long productId,
        @RequestBody @Valid ProductSizeChartUpsertRequest request
    ) {
        return ResponseEntity.ok(Response.success(sizeChartAdminUseCase.replaceProductSizeChart(productId, request.toCommand())));
    }
}
