package com.commercefoundry.domainlayer.product.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductDetailResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductListItemResponse;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.in.ProductWebUseCase;
import com.commercefoundry.persistence.dto.SliceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 상품 조회. 파라미터 검증은 Spring MVC 의 메서드 검증(제약 애노테이션이 붙은 파라미터)이 한다 — 클래스에 {@code @Validated} 를 달지 않는다
 * ({@code coding-conventions.md} §8-2). 달면 AOP 검증으로 바뀌어 {@code ConstraintViolationException} 이 나고 봉투 없는 500 이 된다.
 */
@Tag(name = "상품", description = "스토어프론트 상품 목록 · 상세")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductWebController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductWebUseCase productWebUseCase;

    @Operation(
        summary = "상품 목록 조회",
        description = "숨긴 상품은 빠지고 판매 종료 상품은 포함한다(saleStatus 로 뱃지). 카테고리 조건은 하위 카테고리를 포함하며 없는 카테고리면 빈 목록이다. "
            + "전체 건수를 세지 않는 Slice 다. 로그인 없이 호출할 수 있다."
    )
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<SliceResponse<ProductListItemResponse>>> searchProducts(
        @Parameter(description = "카테고리 아이디 — 하위 카테고리 포함. 없으면 전체") @RequestParam(required = false) Long categoryId,
        @Parameter(description = "정렬 — NEWEST(최신순, 기본) · PRICE_ASC(낮은 가격순) · PRICE_DESC(높은 가격순). 같은 값끼리는 최신순")
        @RequestParam(defaultValue = "NEWEST") ProductSortType sortType,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = ProductValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = ProductValidationMessage.SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = ProductValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(productWebUseCase.searchProducts(categoryId, sortType, page, size)));
    }

    @Operation(
        summary = "상품 상세 조회",
        description = "없거나 숨긴 상품은 404(PRODUCT_001)다. 판매 종료 상품은 200 이고 saleStatus 가 STOP_SELLING 이다. 로그인 없이 호출할 수 있다."
    )
    @PreAuthorize("permitAll()")
    @GetMapping("/{productId}")
    public ResponseEntity<Response<ProductDetailResponse>> getProduct(@Parameter(description = "상품 아이디") @PathVariable long productId) {
        return ResponseEntity.ok().body(Response.success(productWebUseCase.getProduct(productId)));
    }
}
