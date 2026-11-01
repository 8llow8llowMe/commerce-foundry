package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentSummaryInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.port.in.OrderInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 결제용 주문 요약 (#14). 호출자는 payment-service 하나다 — 결제 준비 때 금액 · 주문명 · 상태 · 결제 기한을 읽는다.
 *
 * <p>사용자 토큰은 internal 로 오지 않으므로 회원 아이디는 payment-service 가 access 토큰으로 확인한 값을 쿼리 파라미터로 받는다
 * ({@code security-guide.md} §9-4). 없는 주문과 다른 회원의 주문은 같은 404({@code ORDER_001})다. 상태가 {@code PENDING_PAYMENT} 가 아니어도 200 이다.
 *
 * <p>서비스 토큰이 없거나 틀린 요청은 internal 보안 체인이 이 컨트롤러에 닿기 전에 401({@code SECURITY_007})로 막는다.
 * 인증을 통과한 요청에서는 메서드 검증이 {@code @PreAuthorize} 보다 먼저 돈다. 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/orders")
public class OrderPaymentSummaryInternalController {

    private final OrderInternalUseCase orderInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_payment-service')")
    @GetMapping("/{orderId}/payment-summary")
    public ResponseEntity<Response<OrderPaymentSummaryInternalResponse>> getPaymentSummary(
        @PathVariable @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) long orderId,
        @RequestParam @Positive(message = OrderValidationMessage.MEMBER_ID_POSITIVE) long memberId
    ) {
        return ResponseEntity.ok().body(Response.success(orderInternalUseCase.getPaymentSummary(orderId, memberId)));
    }
}
