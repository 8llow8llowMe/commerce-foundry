package com.commercefoundry.domainlayer.picking.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request.PickingBatchCreateRequest;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request.PickingOrderCheckRequest;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.application.port.in.PickingBatchAdminUseCase;
import com.commercefoundry.domainlayer.picking.application.exception.PickingValidationMessage;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 출고 묶음", description = "출고 묶음 생성·상세 조회와 주문별 포장 검수")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping(PickingBatchAdminController.PATH)
public class PickingBatchAdminController {
    static final String PATH = "/api/v1/admin/deliveries/picking-batches";

    private final PickingBatchAdminUseCase useCase;

    @Operation(
        summary = "출고 묶음 생성",
        description = "서로 다른 주문 1~20건을 판정한다. 상품 준비 중인 국내 일반 주문을 담고, 제외 주문과 사유를 함께 돌려준다. "
            + "대상 주문이 없으면 409(DELIVERY_033), 주문 정보를 읽지 못하면 503(DELIVERY_036)이다."
    )
    @PreAuthorize(AdminAuthority.FULFILLMENT_MANAGE)
    @AdminAudited(action = "PICKING_BATCH_CREATE")
    @PostMapping
    public ResponseEntity<Response<PickingBatchAdminResponse>> create(
        @AuthenticationPrincipal MemberPrincipal member,
        @Valid @RequestBody PickingBatchCreateRequest request
    ) {
        PickingBatchAdminResponse response = useCase.create(request.toOrderIds(), member.memberId());
        return ResponseEntity.created(URI.create(PATH + "/" + response.batchId())).body(Response.success(response));
    }

    @Operation(summary = "출고 묶음 상세", description = "상품 옵션별 피킹 수량, 대상·제외 주문, 검수 이력을 조회한다. 없는 묶음은 404(DELIVERY_034)다.")
    @PreAuthorize(AdminAuthority.FULFILLMENT_READ)
    @GetMapping("/{batchId}")
    public ResponseEntity<Response<PickingBatchAdminResponse>> detail(@Parameter(description = "출고 묶음 식별자", example = "4611686018427387904") @PathVariable long batchId) {
        return ResponseEntity.ok(Response.success(useCase.detail(batchId)));
    }

    @Operation(
        summary = "주문별 포장 검수",
        description = "상품 옵션별로 센 수량을 제출한다. 수량 부족·초과·다른 옵션은 200 응답의 검수 이력에 실패 사유를 남긴다. "
            + "같은 주문에 같은 수량을 다시 보내면 이력을 추가하지 않는다. 동시 저장 충돌은 409(DELIVERY_037)다."
    )
    @PreAuthorize(AdminAuthority.FULFILLMENT_MANAGE)
    @AdminAudited(action = "PICKING_ORDER_CHECK")
    @PostMapping("/{batchId}/orders/{orderId}/checks")
    public ResponseEntity<Response<PickingBatchAdminResponse>> check(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "출고 묶음 식별자", example = "4611686018427387904") @PathVariable long batchId,
        @Parameter(description = "주문 식별자") @PathVariable @Positive(message = PickingValidationMessage.ORDER_ID) long orderId,
        @Valid @RequestBody PickingOrderCheckRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.check(batchId, orderId, request.toQuantities(), member.memberId())));
    }
}
