package com.commercefoundry.domainlayer.payment.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.request.PaymentCancelExternalRefundRequest;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.request.PaymentCancelRequest;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.request.PaymentCancelVoidRequest;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelExternalRefundResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelVoidResponse;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationExternalRefundUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationVoidUseCase;
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
    private final PaymentCancellationVoidUseCase paymentCancellationVoidUseCase;
    private final PaymentCancellationExternalRefundUseCase paymentCancellationExternalRefundUseCase;

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

    /**
     * 결제 후 취소 클레임의 무환불 판정 · 봉인 (#691, {@code payment-guide.md} §8-1). 판정은 200 데이터({@code outcome})다.
     * 멱등 키 헤더가 없다 — 경로의 claimId 가 키이고, 다시 부르면 PG 잔액까지 새로 대조한 현재 판정을 답한다.
     * 본문 {@code orderId} 는 결제의 주문과 대조한다 — order 가 다른 주문의 결제를 가리키면 409 {@code PAYMENT_019} 다.
     */
    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping("/{paymentId}/cancellations/{claimId}/void")
    public ResponseEntity<Response<PaymentCancelVoidResponse>> voidCancellation(
        @PathVariable @Positive(message = PaymentValidationMessage.PAYMENT_ID_POSITIVE) long paymentId,
        @PathVariable @Positive(message = PaymentValidationMessage.CLAIM_ID_POSITIVE) long claimId,
        @Valid @RequestBody PaymentCancelVoidRequest request
    ) {
        return ResponseEntity.ok(Response.success(paymentCancellationVoidUseCase.voidCancellation(
            paymentId, request.orderId(), claimId, request.amount(), request.currency())));
    }

    /**
     * 외부 확인 환불 기록 (#693, {@code payment-guide.md} §8). order 어드민의 수동 환불 확정 전에 부른다 — 신고 채널(PG · PG 밖)을 PG 잔액으로 검증한 뒤에만
     * 원장에 남긴다. 판정은 200 데이터({@code outcome})다. 멱등 키 헤더가 없다 — 경로의 claimId 가 키이고, 기록된 claimId 를 다시 부르면 PG 를 다시 조회하지 않고
     * 같은 답(기록된 채널)을 준다. 본문 {@code orderId} 는 결제의 주문과 대조한다.
     */
    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping("/{paymentId}/cancellations/{claimId}/external-refund")
    public ResponseEntity<Response<PaymentCancelExternalRefundResponse>> recordExternalRefund(
        @PathVariable @Positive(message = PaymentValidationMessage.PAYMENT_ID_POSITIVE) long paymentId,
        @PathVariable @Positive(message = PaymentValidationMessage.CLAIM_ID_POSITIVE) long claimId,
        @Valid @RequestBody PaymentCancelExternalRefundRequest request
    ) {
        return ResponseEntity.ok(Response.success(paymentCancellationExternalRefundUseCase.recordExternalRefund(
            paymentId, request.orderId(), claimId, request.amount(), request.currency(), request.channelValue())));
    }

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @GetMapping("/{paymentId}")
    public ResponseEntity<Response<PaymentInternalResponse>> getPayment(
        @PathVariable @Positive(message = PaymentValidationMessage.PAYMENT_ID_POSITIVE) long paymentId
    ) {
        return ResponseEntity.ok().body(Response.success(paymentInternalUseCase.getPayment(paymentId)));
    }
}
