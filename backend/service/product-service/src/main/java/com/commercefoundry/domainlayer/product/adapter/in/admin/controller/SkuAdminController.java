package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.OptionRetirementRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.SkuAddRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.SkuBulkUpdateRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/products")
@Tag(name = "[어드민] 상품 SKU", description = "SKU 편집 · 추가 · 옵션 은퇴")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
public class SkuAdminController {
    private final SkuAdminUseCase skuAdminUseCase;

    @Operation(summary = "상품 SKU 편집 속성 전체 교체", description = "기존 SKU 전체의 추가금·판매 상태·무게·HS 코드를 바꾸고 편집용 상세를 반환한다. "
        + "동일 값도 상품 버전이 증가한다. 삭제 상품 404 SKU_ADMIN_001, 버전 충돌 409 SKU_ADMIN_002, "
        + "SKU 집합 오류 400 SKU_ADMIN_003, 가격 오류 400 SKU_ADMIN_004, 잠금 충돌 409 SKU_ADMIN_005, 은퇴 옵션 판매중 400 SKU_ADMIN_006이다.")
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @AdminAudited(action = "PRODUCT_SKUS_REPLACE")
    @PutMapping("/{productId}/skus")
    public ResponseEntity<Response<ProductDetailAdminResponse>> replace(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "상품 아이디") @PathVariable @Positive(message = SkuAdminValidationMessage.PRODUCT_ID) long productId,
        @RequestBody @Valid SkuBulkUpdateRequest request
    ) {
        return ResponseEntity.ok(Response.success(skuAdminUseCase.replace(request.toCommand(productId, member.memberId()))));
    }

    @Operation(summary = "옵션 값 · 새 조합 SKU 추가", description = "기존 옵션에 값을 더하고 새 조합 SKU 를 만든 뒤 201 과 편집용 상세를 돌려준다. "
        + "기존 옵션 · SKU · 재고는 그대로다. 새 옵션(그룹)은 더할 수 없다 — 구조를 바꾸려면 새 상품으로 등록한다. "
        + "SKU 는 옵션 노출 순서대로 값 이름을 고르고, 새 값은 이번 요청의 이름으로 고른다. initialStock 을 주면 재고와 입고 이력이 함께 생기고, 주지 않으면 품절로 생긴다. "
        + "삭제 상품 404 SKU_ADMIN_001, 버전 충돌 409 SKU_ADMIN_002, 가격 규칙 400 SKU_ADMIN_004, 잠금 충돌 409 SKU_ADMIN_005, "
        + "옵션 값(없는 옵션 · 이미 쓴 이름 · 은퇴한 이름 · 50개 초과) 400 SKU_ADMIN_007, 조합(값 수 · 없는 값 · 옵션 없는 상품 · 은퇴 옵션이 있는 상품) 400 SKU_ADMIN_008, "
        + "이미 있는 조합(판매 종료 포함) 400 SKU_ADMIN_009, 상품당 100개 초과 400 SKU_ADMIN_010 이다. 같은 요청을 두 번 보내면 두 번째는 409 SKU_ADMIN_002 다.")
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @AdminAudited(action = "PRODUCT_SKUS_ADD")
    @PostMapping("/{productId}/skus")
    public ResponseEntity<Response<ProductDetailAdminResponse>> add(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "상품 아이디") @PathVariable @Positive(message = SkuAdminValidationMessage.PRODUCT_ID) long productId,
        @RequestBody @Valid SkuAddRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(skuAdminUseCase.add(request.toCommand(productId, member.memberId()))));
    }

    @Operation(summary = "옵션 · 옵션 값 은퇴", description = "고른 옵션(그룹) · 값을 더 이상 팔지 않게 은퇴시키고 편집용 상세를 돌려준다. "
        + "은퇴한 값(은퇴한 옵션의 값 포함)을 쓰는 판매중 SKU 는 함께 판매 종료되고, 고객 상세에서 빠진다. 행 · 재고 · 지난 주문의 옵션명은 남는다. "
        + "옵션(그룹)을 은퇴하면 그 옵션을 쓰는 SKU 전부, 즉 상품의 모든 SKU 가 판매 종료된다. 이미 은퇴한 대상은 그대로 두고, 은퇴 되돌리기는 없다. "
        + "삭제 상품 404 SKU_ADMIN_001, 버전 충돌 409 SKU_ADMIN_002, 남는 판매중 SKU 로 가격 규칙이 깨지면 400 SKU_ADMIN_004(추가금을 먼저 맞춘다), "
        + "잠금 충돌 409 SKU_ADMIN_005, 이 상품의 옵션 · 값이 아니면 400 SKU_ADMIN_011, 대상 없음 · 50개 초과 400 SKU_ADMIN_116 이다.")
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @AdminAudited(action = "PRODUCT_OPTIONS_RETIRE")
    @PostMapping("/{productId}/option-retirements")
    public ResponseEntity<Response<ProductDetailAdminResponse>> retireOptions(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "상품 아이디") @PathVariable @Positive(message = SkuAdminValidationMessage.PRODUCT_ID) long productId,
        @RequestBody @Valid OptionRetirementRequest request
    ) {
        return ResponseEntity.ok(Response.success(skuAdminUseCase.retireOptions(request.toCommand(productId, member.memberId()))));
    }
}
