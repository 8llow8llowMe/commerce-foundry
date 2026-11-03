package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.SkuBulkUpdateRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/products")
@Tag(name = "[어드민] 상품 SKU", description = "기존 SKU 편집")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
public class SkuAdminController {
    private final SkuAdminUseCase skuAdminUseCase;

    @Operation(summary = "상품 SKU 편집 속성 전체 교체", description = "기존 SKU 전체의 추가금·판매 상태·무게·HS 코드를 바꾸고 편집용 상세를 반환한다. "
        + "동일 값도 상품 버전이 증가한다. 삭제 상품 404 SKU_ADMIN_001, 버전 충돌 409 SKU_ADMIN_002, "
        + "SKU 집합 오류 400 SKU_ADMIN_003, 가격 오류 400 SKU_ADMIN_004, 잠금 충돌 409 SKU_ADMIN_005, 은퇴 옵션 판매중 400 SKU_ADMIN_006이다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "PRODUCT_SKUS_REPLACE")
    @PutMapping("/{productId}/skus")
    public ResponseEntity<Response<ProductDetailAdminResponse>> replace(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "상품 아이디") @PathVariable @Positive(message = SkuAdminValidationMessage.PRODUCT_ID) long productId,
        @RequestBody @Valid SkuBulkUpdateRequest request
    ) {
        return ResponseEntity.ok(Response.success(skuAdminUseCase.replace(request.toCommand(productId, member.memberId()))));
    }
}
