package com.commercefoundry.domainlayer.sizechart.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.response.ProductSizeChartResponse;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartUnitCode;
import com.commercefoundry.domainlayer.sizechart.application.port.in.ProductSizeChartWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 상품 사이즈표. 상품 상세 본문에 넣지 않는다 — 공개 상품 상세 계약과 갈라 두어 상세 응답을 바꾸지 않기 위해서다.
 * 단위는 domain enum 이 아니라 application/model 입력 enum 으로 받는다 (H1).
 * 없거나 숨긴 상품은 상품 상세와 같이 404(PRODUCT_001)이고, 보이는데 표가 없으면 200 · dataBody null 이다.
 */
@Tag(name = "상품 사이즈표", description = "스토어프론트 상품 사이즈표")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/{productId}/size-chart")
public class ProductSizeChartWebController {

    private final ProductSizeChartWebUseCase productSizeChartWebUseCase;

    @Operation(
        summary = "상품 사이즈표 조회",
        description = "없거나 숨긴 상품은 404(PRODUCT_001)다. 보이는 상품에 표가 없으면 200 이고 dataBody 는 null 이다. "
            + "unit 을 빼면 저장 단위 그대로이고, CM 또는 INCH 이면 그 단위로 바꾼 값을 낸다. 로그인 없이 호출할 수 있다."
    )
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<ProductSizeChartResponse>> getSizeChart(
        @Parameter(description = "상품 아이디") @PathVariable long productId,
        @Parameter(description = "표시 단위 — CM · INCH. 없으면 저장 단위") @RequestParam(required = false) SizeChartUnitCode unit
    ) {
        return ResponseEntity.ok(Response.success(productSizeChartWebUseCase.getSizeChart(productId, unit)));
    }
}
