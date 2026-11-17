package com.commercefoundry.domainlayer.picking.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request.PickingBatchCreateRequest;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request.PickingOrderCheckRequest;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.application.port.in.PickingBatchAdminUseCase;
import com.commercefoundry.security.principal.AdminAuthority;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import com.commercefoundry.domainlayer.picking.application.exception.PickingValidationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 피킹", description = "주문 피킹 batch 생성·상세·검수. 송장 등록·출고와 별도 흐름이다.")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/deliveries/picking-batches")
public class PickingBatchAdminController {
    private final PickingBatchAdminUseCase useCase;

    @Operation(summary = "피킹 batch 생성", description = "PREPARING 상태이며 국내 일반 상품인 1~20개 주문의 피킹 스냅숏을 저장한다.")
    @PreAuthorize(AdminAuthority.FULFILLMENT_MANAGE)
    @PostMapping
    public ResponseEntity<Response<PickingBatchAdminResponse>> create(@Valid @RequestBody PickingBatchCreateRequest request) {
        return ResponseEntity.status(201).body(Response.success(useCase.create(request.toOrderIds())));
    }

    @Operation(summary = "피킹 batch 상세")
    @PreAuthorize(AdminAuthority.FULFILLMENT_READ)
    @GetMapping("/{batchId}")
    public ResponseEntity<Response<PickingBatchAdminResponse>> detail(@Parameter(description = "batch 식별자") @PathVariable long batchId) {
        return ResponseEntity.ok(Response.success(useCase.detail(batchId)));
    }

    @Operation(summary = "주문별 수량 검수", description = "같은 SKU별 수량은 이력을 중복 기록하지 않는다. 실패 사유와 이전 이력을 보존한다.")
    @PreAuthorize(AdminAuthority.FULFILLMENT_MANAGE)
    @PostMapping("/{batchId}/orders/{orderId}/checks")
    public ResponseEntity<Response<PickingBatchAdminResponse>> check(
        @Parameter(description = "batch 식별자") @PathVariable long batchId,
        @Parameter(description = "주문 식별자") @PathVariable @Positive(message = PickingValidationMessage.ORDER_ID) long orderId,
        @Valid @RequestBody PickingOrderCheckRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.check(batchId, orderId, request.toQuantities())));
    }
}
