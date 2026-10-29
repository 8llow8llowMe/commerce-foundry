package com.commercefoundry.domainlayer.skucost.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.item.SkuCostAdminItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.request.SkuCostCreateRequest;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.response.SkuCurrentCostsAdminResponse;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostValidationMessage;
import com.commercefoundry.domainlayer.skucost.application.model.SkuCostTypeCode;
import com.commercefoundry.domainlayer.skucost.application.port.in.SkuCostAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 SKU 원가 이력. 원가는 고치지 않고 적용 시작 시각이 있는 행을 더한다(append-only) — 주문 시점 원가를 나중에 그대로 다시 고를 수 있게 한다.
 * {@code /api/v1/admin/**} 은 URL 규칙이 ADMIN 을 먼저 요구하고, 메서드의 {@code hasRole('ADMIN')} 이 이중으로 막는다 ({@code security-guide.md} §5-1).
 * 기록한 어드민은 access 토큰의 memberId 로만 남긴다 — 본문으로 받지 않는다. 숨긴 상품의 SKU 도 다룬다. 없는 SKU 는 404(PRODUCT_002)다.
 *
 * <p>파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] SKU 원가", description = "SKU 원가(매입 · 제작 원가, 포장 변동비) 적용 이력 조회 · 추가")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/skus/{skuId}/costs")
public class SkuCostAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SkuCostAdminUseCase skuCostAdminUseCase;

    @Operation(
        summary = "SKU 현재 원가 조회",
        description = "지금 적용되는 원가를 유형별(GOODS → PACKAGING)로 하나씩 낸다. 한 번도 입력하지 않은 유형은 항목이 없다 — 0원으로 채우지 않는다. "
            + "없는 SKU 는 404(PRODUCT_002)다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<SkuCurrentCostsAdminResponse>> getCurrentCosts(
        @Parameter(description = "SKU 아이디") @PathVariable @Positive(message = SkuCostValidationMessage.SKU_ID_POSITIVE) long skuId
    ) {
        return ResponseEntity.ok(Response.success(skuCostAdminUseCase.getCurrentCosts(skuId)));
    }

    @Operation(
        summary = "SKU 원가 이력 조회",
        description = "적용 시작 시각 내림차순(같으면 아이디 내림차순)으로 페이지 단위로 읽는다. costType 을 주면 그 유형만이다. "
            + "없는 SKU 는 404(PRODUCT_002), 이력이 없으면 빈 목록이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/histories")
    public ResponseEntity<Response<PageResponse<SkuCostAdminItem>>> getHistories(
        @Parameter(description = "SKU 아이디") @PathVariable @Positive(message = SkuCostValidationMessage.SKU_ID_POSITIVE) long skuId,
        @Parameter(description = "원가 유형 (GOODS · PACKAGING). 없으면 모든 유형") @RequestParam(required = false) SkuCostTypeCode costType,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = SkuCostValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = SkuCostValidationMessage.SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = SkuCostValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok(Response.success(skuCostAdminUseCase.getHistories(skuId, costType, page, size)));
    }

    @Operation(
        summary = "SKU 원가 이력 추가",
        description = "새 적용 시작 시각으로 원가 행을 하나 더한다. effectiveFrom 을 생략하면 지금이다. 같은 유형의 최신 이력 시작보다 뒤가 아니면 409(PRODUCT_075), "
            + "지금부터 5분보다 뒤면 400(PRODUCT_074), 기존 이력과 통화가 다르면 409(PRODUCT_076), 같은 시작 시각이 먼저 저장됐으면 409(PRODUCT_077), "
            + "다른 원가 저장과 겹쳐 잠금을 얻지 못하면 409(PRODUCT_078 — 다시 시도)다. 없는 SKU 는 404(PRODUCT_002)다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Response<SkuCostAdminItem>> createCost(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "SKU 아이디") @PathVariable @Positive(message = SkuCostValidationMessage.SKU_ID_POSITIVE) long skuId,
        @RequestBody @Valid SkuCostCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(skuCostAdminUseCase.createCost(request.toCommand(skuId, member.memberId()))));
    }
}
