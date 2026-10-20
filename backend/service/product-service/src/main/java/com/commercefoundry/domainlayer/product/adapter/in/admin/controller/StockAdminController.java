package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAdjustmentHistoryAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.StockAdjustmentRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminListResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.StockAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 재고 관리. {@code /api/v1/admin/**} 은 URL 규칙이 ADMIN 을 먼저 요구하고, 메서드의 {@code hasRole('ADMIN')} 이 이중으로 막는다
 * ({@code security-guide.md} §5-1). 조정한 어드민은 access 토큰의 memberId 로만 기록한다 — 본문으로 받지 않는다.
 *
 * <p>파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 재고 관리", description = "SKU 재고 조회 · 수량과 재고 관리 여부 조정 · 조정 이력")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/stocks")
public class StockAdminController {

    /** 한 번에 받는 SKU 아이디 상한 — 중복을 포함한 원문 개수로 센다. 서비스 간 SKU 벌크 조회와 같다. */
    private static final int MAX_SKU_IDS = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final StockAdminUseCase stockAdminUseCase;

    @Operation(
        summary = "SKU 재고 조회",
        description = "SKU 아이디 목록으로 재고를 한 번에 읽는다 (1~100개, skuIds=1,2,3). 요청 순서대로이고 중복은 첫 등장 한 번만, 없는 SKU 는 응답에서 빠진다. "
            + "재고를 등록하지 않은 SKU 는 품절(stockManaged=true, availableQuantity=0, reservedQuantity=0, updatedAt=null)이다. "
            + "availableQuantity 를 그대로 조정의 expectedQuantity 로 보내면 된다 (재고 미등록 SKU 는 0)."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<StockAdminListResponse>> getStocks(
        @Parameter(description = "SKU 아이디 목록 (1~100개, 쉼표 구분)", example = "1001,1002")
        @RequestParam
        @NotEmpty(message = ProductValidationMessage.STOCK_SKU_IDS_REQUIRED)
        @Size(max = MAX_SKU_IDS, message = ProductValidationMessage.STOCK_SKU_IDS_SIZE_MAX)
        List<@NotNull(message = ProductValidationMessage.STOCK_SKU_IDS_POSITIVE) @Positive(message = ProductValidationMessage.STOCK_SKU_IDS_POSITIVE) Long> skuIds
    ) {
        return ResponseEntity.ok().body(Response.success(stockAdminUseCase.getStocks(skuIds)));
    }

    @Operation(
        summary = "SKU 재고 조정 이력 조회",
        description = "SKU 하나의 조정 이력을 최신순으로 페이지 단위로 읽는다. 없는 SKU 는 404(PRODUCT_002), 이력이 없는 SKU 는 빈 목록이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{skuId}/adjustments")
    public ResponseEntity<Response<PageResponse<StockAdjustmentHistoryAdminItem>>> getAdjustments(
        @Parameter(description = "SKU 아이디") @PathVariable @Positive(message = ProductValidationMessage.STOCK_SKU_ID_POSITIVE) long skuId,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = ProductValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = ProductValidationMessage.SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = ProductValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(stockAdminUseCase.getAdjustments(skuId, page, size)));
    }

    @Operation(
        summary = "SKU 재고 조정",
        description = "수량은 절대값으로 바꾸고, 화면에서 본 현재 수량을 expectedQuantity 로 함께 보낸다. 그 사이 주문 · 다른 어드민이 수량을 바꿨으면 "
            + "409(PRODUCT_020)이며 현재 수량을 다시 읽어 조정한다. 재고를 처음 등록하는 SKU(재고 없음 = 품절)는 expectedQuantity 가 0 이다. "
            + "없는 SKU 는 404(PRODUCT_002). 요청마다 조정 이력을 한 건 남긴다. availableQuantity 는 결제 전 선점분(reservedQuantity)이 빠진 값이라 "
            + "실사 수량을 입력할 때는 실물 − reservedQuantity 를 quantity 로 보낸다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{skuId}")
    public ResponseEntity<Response<StockAdminResponse>> adjustStock(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "SKU 아이디") @PathVariable @Positive(message = ProductValidationMessage.STOCK_SKU_ID_POSITIVE) long skuId,
        @RequestBody @Valid StockAdjustmentRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(stockAdminUseCase.adjustStock(request.toCommand(skuId, member.memberId()))));
    }
}
