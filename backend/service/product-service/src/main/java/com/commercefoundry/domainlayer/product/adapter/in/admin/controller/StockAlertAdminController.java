package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAlertAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.StockAlertPolicyRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAlertPolicyResponse;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.StockAlertAdminUseCase;
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
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 품절 임박 기준", description = "SKU별 기준 수량 설정과 품절 임박 조회. 알림 발송은 후속 기능")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/stocks")
public class StockAlertAdminController {

    private final StockAlertAdminUseCase useCase;

    @Operation(summary = "SKU 품절 임박 기준 조회", description = "미설정은 enabled=false. 없는 SKU·삭제 상품은 404(STOCK_ALERT_001).")
    @PreAuthorize(AdminAuthority.INVENTORY_READ)
    @GetMapping("/{skuId}/low-stock-policy")
    public ResponseEntity<Response<StockAlertPolicyResponse>> getPolicy(
        @Parameter(description = "SKU 아이디") @PathVariable @Positive(message = StockAlertValidationMessage.SKU_ID_POSITIVE) long skuId
    ) {
        return ResponseEntity.ok(Response.success(useCase.getPolicy(skuId)));
    }

    @Operation(summary = "SKU 품절 임박 기준 설정", description = "기준 수량은 0 이상. 재고는 변경하지 않는다. 없는 SKU·삭제 상품은 404(STOCK_ALERT_001), 잠금 경합은 409(STOCK_ALERT_002).")
    @PreAuthorize(AdminAuthority.INVENTORY_MANAGE)
    @PutMapping("/{skuId}/low-stock-policy")
    @AdminAudited(action = "STOCK_ALERT_POLICY_SET")
    public ResponseEntity<Response<StockAlertPolicyResponse>> setPolicy(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "SKU 아이디") @PathVariable @Positive(message = StockAlertValidationMessage.SKU_ID_POSITIVE) long skuId,
        @RequestBody @Valid StockAlertPolicyRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.setPolicy(skuId, request.thresholdQuantity(), member.memberId())));
    }

    @Operation(summary = "SKU 품절 임박 기준 해제", description = "미설정도 성공한다. 없는 SKU·삭제 상품은 404(STOCK_ALERT_001), 잠금 경합은 409(STOCK_ALERT_002).")
    @PreAuthorize(AdminAuthority.INVENTORY_MANAGE)
    @DeleteMapping("/{skuId}/low-stock-policy")
    @AdminAudited(action = "STOCK_ALERT_POLICY_CLEAR")
    public ResponseEntity<Response<Void>> clearPolicy(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "SKU 아이디") @PathVariable @Positive(message = StockAlertValidationMessage.SKU_ID_POSITIVE) long skuId
    ) {
        useCase.clearPolicy(skuId, member.memberId());
        return ResponseEntity.ok(Response.success(null));
    }

    @Operation(summary = "품절 임박 SKU 조회", description = "설정된 SKU 중 판매 가능 수량이 기준 이하인 항목. 무제한·삭제 상품은 제외하고 숨김·판매 중지는 상태와 함께 포함한다. 수량→SKU 아이디 오름차순.")
    @PreAuthorize(AdminAuthority.INVENTORY_READ)
    @GetMapping("/low")
    public ResponseEntity<Response<PageResponse<StockAlertAdminItem>>> getLowStocks(
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = StockAlertValidationMessage.PAGE_NON_NEGATIVE) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = StockAlertValidationMessage.SIZE_RANGE) @Max(value = 100, message = StockAlertValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok(Response.success(useCase.getLowStocks(page, size)));
    }
}
