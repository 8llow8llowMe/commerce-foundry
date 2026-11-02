package com.commercefoundry.domainlayer.shipment.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.request.ShipmentRegisterRequest;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 송장(배송) — 송장 등록 · 출고 (#18 PR-B1, {@code order-flow-guide.md} §4). 배송 상태 변경 · 조회 · 목록은 PR-B2 가 같은 경로 아래에 더한다.
 * 등록한 관리자는 access 토큰의 memberId 로만 남긴다 — 본문으로 받지 않는다.
 *
 * <p>본문 검증은 {@code @Valid @RequestBody} 가 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 배송", description = "송장 등록(출고) — 주문을 배송 중으로 반영한다")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/deliveries")
public class ShipmentAdminController {

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
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "SHIPMENT_REGISTER")
    @PostMapping
    public ResponseEntity<Response<ShipmentRegisterAdminResponse>> registerShipments(
        @AuthenticationPrincipal MemberPrincipal member,
        @Valid @RequestBody ShipmentRegisterRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(shipmentAdminUseCase.register(request.toCommand(member.memberId()))));
    }
}
