package com.commercefoundry.domainlayer.shipment.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentSummaryAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.request.ShipmentRegisterRequest;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.request.ShipmentStatusChangeRequest;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentAdminResponse;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentValidationMessage;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentAdminUseCase;
import com.commercefoundry.domainlayer.shipment.application.model.ShipmentStatusFilter;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryValidationMessage;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 송장(배송) — 송장 등록 · 출고 (#18 PR-B1), 배송 상태 변경 · 상세 · 목록 (#18 PR-B2, {@code order-flow-guide.md} §4).
 * 등록 · 변경한 관리자는 access 토큰의 memberId 로만 남긴다 — 본문으로 받지 않는다.
 *
 * <p>본문 검증은 {@code @Valid @RequestBody}, 쿼리 파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다
 * ({@code coding-conventions.md} §8-2). 경로의 deliveryId 는 제약을 달지 않는다 — 숫자가 아니면 {@code DELIVERY_198}, 0 이하는 없는 배송(404)이다.
 */
@Tag(name = "[어드민] 배송", description = "송장 등록(출고) · 배송 상태 변경(배송 중 · 배송 완료) · 송장 상세 · 목록")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/deliveries")
public class ShipmentAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ShipmentAdminUseCase shipmentAdminUseCase;

    @Operation(
        summary = "송장 일괄 등록 (출고)",
        description = "주문마다 송장(택배사 · 송장 번호)을 등록하고 주문을 상품 준비중 → 배송 중으로 반영한다. 1~20건, 단건도 1건짜리 목록으로 보낸다. "
            + "주문마다 따로 처리해 일부가 실패해도 200 이고 results 가 요청 순서대로 건별 결과다 — REGISTERED(등록) · ALREADY_REGISTERED(같은 송장으로 이미 출고됨, 그대로 둠) · "
            + "FAILED(errorCode · message). 실패 코드: 없는 주문 DELIVERY_014, 출고할 수 없는 주문 상태(결제 전 · 취소 요청 · 취소 등) DELIVERY_015, "
            + "서비스 시각 차이로 출고 시각이 거절됨 DELIVERY_016, 그 사이 주문이 바뀜 DELIVERY_017(같은 송장으로 다시), 주문 서비스 응답 없음 DELIVERY_018(같은 송장으로 다시), "
            + "다른 송장으로 이미 출고됨 DELIVERY_019, 같은 주문 동시 등록 DELIVERY_020, 저장 실패 DELIVERY_021, "
            + "요청 처리 시간(8초)을 넘겨 시작하지 못함 DELIVERY_022(송장을 만들지 않음). "
            + "017 · 018 · 020 · 021 · 022 는 같은 요청을 다시 보내면 된다 — 주문에 반영되기 전의 송장은 다시 보낸 택배사 · 송장 번호로 바뀐다(출고 시각은 처음 값). 출고된 송장은 바꿀 수 없다. "
            + "본문 오류는 400 — items 1~20(DELIVERY_140) · null 원소(141) · orderId 숫자 문자열(142) · 같은 주문 중복(143) · carrier 없음(144) · 송장 번호 형식(145), "
            + "모르는 택배사 · 깨진 JSON 은 DELIVERY_100."
    )
    @PreAuthorize(AdminAuthority.FULFILLMENT_MANAGE)
    @AdminAudited(action = "SHIPMENT_REGISTER")
    @PostMapping
    public ResponseEntity<Response<ShipmentRegisterAdminResponse>> registerShipments(
        @AuthenticationPrincipal MemberPrincipal member,
        @Valid @RequestBody ShipmentRegisterRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(shipmentAdminUseCase.register(request.toCommand(member.memberId()))));
    }

    @Operation(
        summary = "송장 목록",
        description = "송장을 최신순(송장 아이디 내림차순)으로 페이지 단위로 읽는다. 조건은 모두 선택이고 함께 쓰면 모두 맞는 것만 나온다. "
            + "orderId 는 그 주문의 송장(주문당 1건이라 0~1건) — 주문 상세 화면이 송장을 찾을 때 쓴다. "
            + "synced=false 는 지금 상태가 주문에 반영되지 않은 송장이다 — 출고 반영 전(orderSynced=false, 같은 송장으로 등록을 다시 보낸다)과 "
            + "배송 완료 반영 대기(completionPending=true, 배송 완료를 다시 보낸다). synced=true 는 그 밖이다. "
            + "배송 상태는 송장 상태 그대로라 주문 상태와 다를 수 있다(주문 화면의 수동 배송 완료 등). "
            + "오류: orderId 1 이상(DELIVERY_142) · page 0 이상(DELIVERY_137) · size 1~100(DELIVERY_149) · 숫자 · 상태 · true/false 형식(DELIVERY_198)."
    )
    @PreAuthorize(AdminAuthority.FULFILLMENT_READ)
    @GetMapping
    public ResponseEntity<Response<PageResponse<ShipmentSummaryAdminItem>>> searchShipments(
        @Parameter(description = "주문 아이디 (숫자)", example = "4611686018427387904")
        @RequestParam(required = false) @Positive(message = ShipmentValidationMessage.ORDER_ID_FORMAT) Long orderId,
        @Parameter(description = "배송 상태 — SHIPPED · IN_TRANSIT · DELIVERED") @RequestParam(required = false) ShipmentStatusFilter status,
        @Parameter(description = "지금 상태가 주문에 반영됐는가 — false 면 반영 대기(다시 보낼 송장)") @RequestParam(required = false) Boolean synced,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = DeliveryValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = ShipmentValidationMessage.PAGE_SIZE_RANGE)
        @Max(value = MAX_PAGE_SIZE, message = ShipmentValidationMessage.PAGE_SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(shipmentAdminUseCase.search(orderId, status, synced, page, size)));
    }

    @Operation(
        summary = "송장 상세",
        description = "송장과 배송 상태 이력(시간순)을 낸다. 배송 상태는 송장 상태 그대로다 — 주문 화면의 수동 배송 완료처럼 배송을 거치지 않은 변경이 있으면 주문 상태와 다를 수 있고, "
            + "그때 배송 완료(DELIVERED)로 바꾸면 주문은 그대로 두고 송장만 맞춰진다. 없는 송장은 404(DELIVERY_023)."
    )
    @PreAuthorize(AdminAuthority.FULFILLMENT_READ)
    @GetMapping("/{deliveryId}")
    public ResponseEntity<Response<ShipmentAdminResponse>> getShipment(
        @Parameter(description = "송장(배송) 아이디", example = "4611686018427387906") @PathVariable long deliveryId
    ) {
        return ResponseEntity.ok().body(Response.success(shipmentAdminUseCase.getDetail(deliveryId)));
    }

    @Operation(
        summary = "배송 상태 변경",
        description = "출고 완료 → 배송 중 · 배송 완료, 배송 중 → 배송 완료로 바꾼다. 응답은 바뀐 상세다. 이미 그 상태면 바꾸지 않고 200 이다. "
            + "배송 중은 배송 정보만 바꾸고, 배송 완료는 주문도 배송 완료로 반영한다(배송 완료 시각 = 지금, 고객의 반품 · 구매 확정 기간이 여기서 시작한다). "
            + "오류: 없는 송장 404(DELIVERY_023), 바꿀 수 없는 전이 409(DELIVERY_024), 출고가 주문에 반영되지 않은 송장 409(DELIVERY_025 — 같은 송장으로 등록을 다시 보내 출고부터), "
            + "그 사이 배송 상태가 바뀜 409(DELIVERY_026). 배송 완료의 주문 반영 실패 — 없는 주문 404(DELIVERY_014) · 배송 완료로 바꿀 수 없는 주문 상태 409(DELIVERY_027) · "
            + "시각 거절 500(DELIVERY_028)은 송장을 직전 상태로 되돌린다. 그 사이 주문이 바뀜 409(DELIVERY_029) · 주문 서비스 응답 없음 503(DELIVERY_030)은 "
            + "배송 완료 반영 대기(completionPending)로 남는다 — 배송 완료를 다시 보내면 처음 배송 완료 시각으로 다시 반영한다. 저장 실패 500(DELIVERY_031). "
            + "본문 오류는 400 — status 는 IN_TRANSIT · DELIVERED(DELIVERY_146) · 사유 200자(147) · 사유 제어 문자(148), 모르는 상태 · 깨진 JSON 은 DELIVERY_100."
    )
    @PreAuthorize(AdminAuthority.FULFILLMENT_MANAGE)
    @AdminAudited(action = "SHIPMENT_STATUS_CHANGE")
    @PatchMapping("/{deliveryId}/status")
    public ResponseEntity<Response<ShipmentAdminResponse>> changeShipmentStatus(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "송장(배송) 아이디", example = "4611686018427387906") @PathVariable long deliveryId,
        @Valid @RequestBody ShipmentStatusChangeRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(shipmentAdminUseCase.changeStatus(request.toCommand(deliveryId, member.memberId()))));
    }
}
