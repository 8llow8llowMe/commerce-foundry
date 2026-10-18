package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.StockAdjustmentRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.application.port.in.StockAdminUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 재고 관리. {@code /api/v1/admin/**} 은 URL 규칙이 ADMIN 을 먼저 요구하고, 메서드의 {@code hasRole('ADMIN')} 이 이중으로 막는다
 * ({@code security-guide.md} §5-1). 조정한 어드민은 access 토큰의 memberId 로만 기록한다 — 본문으로 받지 않는다.
 */
@Tag(name = "[어드민] 재고 관리", description = "SKU 재고 수량 · 재고 관리 여부 조정")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/stocks")
public class StockAdminController {

    private final StockAdminUseCase stockAdminUseCase;

    @Operation(
        summary = "SKU 재고 조정",
        description = "수량은 절대값으로 바꾸고, 화면에서 본 현재 수량을 expectedQuantity 로 함께 보낸다. 그 사이 주문 · 다른 어드민이 수량을 바꿨으면 "
            + "409(PRODUCT_020)이며 현재 수량을 다시 읽어 조정한다. 재고를 처음 등록하는 SKU(재고 없음 = 품절)는 expectedQuantity 가 0 이다. "
            + "없는 SKU 는 404(PRODUCT_002). 요청마다 조정 이력을 한 건 남긴다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{skuId}")
    public ResponseEntity<Response<StockAdminResponse>> adjustStock(
        @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "SKU 아이디") @PathVariable long skuId,
        @RequestBody @Valid StockAdjustmentRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(stockAdminUseCase.adjustStock(request.toCommand(skuId, member.memberId()))));
    }
}
