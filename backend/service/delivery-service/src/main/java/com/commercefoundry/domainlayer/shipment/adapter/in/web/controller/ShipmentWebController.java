package com.commercefoundry.domainlayer.shipment.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shipment.adapter.in.web.dto.response.ShipmentResponse;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentValidationMessage;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentWebUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 배송 조회 (#18 PR-B2) — 주문 상세 화면이 송장 · 배송 상태를 붙일 때 부른다. 회원은 토큰 주체다.
 *
 * <p>파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "배송", description = "스토어프론트 주문 배송 조회")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/deliveries")
public class ShipmentWebController {

    private final ShipmentWebUseCase shipmentWebUseCase;

    @Operation(
        summary = "주문 배송 조회",
        description = "내 주문의 송장(택배사 · 송장 번호) · 배송 상태 · 출고 · 배송 완료 시각을 낸다. 아직 출고 전이면 dataBody 가 null 이다 — "
            + "없는 주문 · 남의 주문도 null 이고 404 를 내지 않는다(주문이 있는지는 주문 상세가 판단한다). "
            + "CUSTOMER 전용이고 ADMIN 은 403 이다. orderId 없음 400(DELIVERY_199) · 숫자 아님(DELIVERY_198) · 1 미만(DELIVERY_142)."
    )
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping
    public ResponseEntity<Response<ShipmentResponse>> getOrderShipment(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "주문 아이디 (숫자)", example = "4611686018427387904")
        @RequestParam @Positive(message = ShipmentValidationMessage.ORDER_ID_FORMAT) long orderId
    ) {
        return ResponseEntity.ok().body(Response.success(shipmentWebUseCase.getOrderShipment(orderId, member.memberId())));
    }
}
