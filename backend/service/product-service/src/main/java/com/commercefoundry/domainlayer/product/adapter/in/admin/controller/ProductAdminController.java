package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.model.ProductAvailabilityFilter;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 상품 관리. {@code /api/v1/admin/**} 은 URL 규칙이 ADMIN 을 먼저 요구하고, 메서드의 {@code hasRole('ADMIN')} 이 이중으로 막는다
 * ({@code security-guide.md} §5-1). 고객 상품 조회({@code ProductWebController})와 DTO 를 공유하지 않는다.
 *
 * <p>파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 * 판매 상태 · 판매 가능 상태 조건은 domain enum 이 아니라 application/model 의 입력 enum 으로 받는다 — 컨트롤러는 domain 을 쓰지 않는다 (H1).
 */
@Tag(name = "[어드민] 상품 관리", description = "숨김 포함 상품 목록")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/products")
public class ProductAdminController {

    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductAdminUseCase productAdminUseCase;

    @Operation(
        summary = "상품 목록 조회 (숨김 포함)",
        description = "어드민 표용 페이지 목록이다(전체 건수 포함). 조건을 주지 않으면 숨김 · 판매 종료 상품까지 전체다. "
            + "keyword 는 상품명 부분 일치(대소문자 무시, 앞뒤 공백은 빼고 비면 조건 없음)이고, categoryId 는 하위 카테고리를 포함하며 없는 카테고리면 빈 목록이다. "
            + "availability 는 재고를 반영한 판매 가능 상태로 고객 화면 뱃지와 같은 규칙이다(재고 미등록 SKU 는 품절, 판매중 SKU 가 없으면 판매 종료). "
            + "가격은 저장된 기본 판매가 · 정가 그대로다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<ProductListAdminItem>>> searchProducts(
        @Parameter(description = "상품명 검색어 — 부분 일치, 대소문자 무시 (100자 이하)") @RequestParam(required = false)
        @Size(max = MAX_KEYWORD_LENGTH, message = ProductValidationMessage.PRODUCT_KEYWORD_SIZE_MAX) String keyword,
        @Parameter(description = "진열 카테고리 아이디 — 하위 카테고리 포함. 없으면 전체") @RequestParam(required = false)
        @Positive(message = ProductValidationMessage.CATEGORY_ID_POSITIVE) Long categoryId,
        @Parameter(description = "저장된 판매 상태 — ON_SALE(판매중) · STOP_SELLING(판매 종료). 없으면 전체") @RequestParam(required = false)
        ProductSaleStatusFilter saleStatus,
        @Parameter(description = "스토어프론트 노출 여부. 없으면 숨김 포함 전체") @RequestParam(required = false) Boolean visible,
        @Parameter(description = "재고를 반영한 판매 가능 상태 — ON_SALE(판매중) · SOLD_OUT(품절) · STOP_SELLING(판매 종료). 없으면 전체")
        @RequestParam(required = false) ProductAvailabilityFilter availability,
        @Parameter(description = "정렬 — NEWEST(최신순, 기본) · NAME_ASC(상품명순). 같은 값끼리는 최신순")
        @RequestParam(defaultValue = "NEWEST") ProductAdminSortType sortType,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = ProductValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = ProductValidationMessage.SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = ProductValidationMessage.SIZE_RANGE) int size
    ) {
        ProductAdminSearchCommand command = ProductAdminSearchCommand.builder()
            .keyword(keyword)
            .categoryId(categoryId)
            .saleStatus(saleStatus)
            .visible(visible)
            .availability(availability)
            .sortType(sortType)
            .page(page)
            .size(size)
            .build();
        return ResponseEntity.ok().body(Response.success(productAdminUseCase.searchProducts(command)));
    }
}
