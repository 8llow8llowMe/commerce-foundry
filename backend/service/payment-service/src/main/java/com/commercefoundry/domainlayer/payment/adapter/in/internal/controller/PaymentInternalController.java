package com.commercefoundry.domainlayer.payment.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.request.PaymentCancelRequest;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelResponse;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationUseCase;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentValidationMessage;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 결제 조회 (#308). 호출자는 order-service 하나다 — {@code payment-approved} 이벤트에는 서명이 없어 order 컨슈머가 이 조회로
 * 상태 · 금액 · 통화 · 주문 · 회원을 다시 확인한다 ({@code payment-guide.md} §6). 회원을 거르지 않는다. 없으면 404 {@code PAYMENT_001} 이다.
 *
 * <p>서비스 토큰이 없거나 틀린 요청(사용자 access 토큰 포함)은 internal 보안 체인이 이 컨트롤러에 닿기 전에 401({@code SECURITY_007})로 막는다.
 * 인증을 통과한 요청에서는 메서드 검증이 {@code @PreAuthorize} 보다 먼저 돈다. 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/payments")
public class PaymentInternalController {

    private final PaymentInternalUseCase paymentInternalUseCase;
    private final PaymentCancellationUseCase paymentCancellationUseCase;

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping("/{paymentId}/cancellations")
    public ResponseEntity<Response<PaymentCancelResponse>> cancelPayment(
        @PathVariable @Positive(message = PaymentValidationMessage.PAYMENT_ID_POSITIVE) long paymentId,
        @Valid @RequestBody PaymentCancelRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(Response.success(paymentCancellationUseCase.cancel(
            paymentId, request.claimId(), request.amount(), request.currency(), idempotencyKey)));
    }

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @GetMapping("/{paymentId}")
    public ResponseEntity<Response<PaymentInternalResponse>> getPayment(
        @PathVariable @Positive(message = PaymentValidationMessage.PAYMENT_ID_POSITIVE) long paymentId
    ) {
        return ResponseEntity.ok().body(Response.success(paymentInternalUseCase.getPayment(paymentId)));
    }
}
