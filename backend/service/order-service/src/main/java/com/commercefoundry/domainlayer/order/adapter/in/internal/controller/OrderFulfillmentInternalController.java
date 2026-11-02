package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.request.OrderDeliveryCompletionInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.request.OrderShipmentInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.port.in.OrderInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 출고 · 배송 완료 반영 (#18). 호출자는 delivery-service 하나다 — 송장 등록 · 배송 완료를 주문 상태(배송 중 · 배송 완료)로 동기 반영한다
 * ({@code order-flow-guide.md} §4).
 *
 * <p>둘 다 멱등이다 — 이미 반영된 뒤의 재요청은 바꾸지 않고 200 으로 지금 상태를 낸다. 반영할 수 없는 상태(취소 · 출고 전 등)는 409 {@code ORDER_020},
 * 없는 주문은 404 {@code ORDER_001}, 읽은 뒤 다른 곳이 먼저 바꿨으면 409 {@code ORDER_025}(다시 보내면 된다)다. 바꿀 때 시각이 지금 기준 30일 전 ~ 5분 뒤 밖이면
 * 400 {@code ORDER_159} · {@code 160}, 배송 완료가 출고보다 앞서면 400 {@code ORDER_161} 이다. 회원 소유는 보지 않는다 — 운영 흐름의 호출이다.
 *
 * <p>서비스 토큰이 없거나 틀린 요청은 internal 보안 체인이 이 컨트롤러에 닿기 전에 401({@code SECURITY_007})로 막는다.
 * 인증을 통과한 요청에서는 메서드 검증이 {@code @PreAuthorize} 보다 먼저 돈다. 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/orders")
public class OrderFulfillmentInternalController {

    private final OrderInternalUseCase orderInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_delivery-service')")
    @PostMapping("/{orderId}/shipment")
    public ResponseEntity<Response<OrderFulfillmentInternalResponse>> ship(
        @PathVariable @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) long orderId,
        @RequestBody @Valid OrderShipmentInternalRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(orderInternalUseCase.ship(orderId, request.deliveryIdValue(), request.shippedAt())));
    }

    @PreAuthorize("hasAuthority('SERVICE_delivery-service')")
    @PostMapping("/{orderId}/delivery-completion")
    public ResponseEntity<Response<OrderFulfillmentInternalResponse>> completeDelivery(
        @PathVariable @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) long orderId,
        @RequestBody @Valid OrderDeliveryCompletionInternalRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(orderInternalUseCase.completeDelivery(orderId, request.deliveryIdValue(), request.deliveredAt())));
    }
}
