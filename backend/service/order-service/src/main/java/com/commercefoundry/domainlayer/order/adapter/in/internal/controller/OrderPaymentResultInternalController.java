package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentResultInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentResultInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/order-payments")
public class OrderPaymentResultInternalController {

    private final OrderPaymentResultInternalUseCase orderPaymentResultInternalUseCase;

    @Operation(summary = "저장된 최초 결제 반영 결과 조회")
    @PreAuthorize("hasAuthority('SERVICE_payment-service')")
    @GetMapping("/{paymentId}")
    public ResponseEntity<Response<OrderPaymentResultInternalResponse>> getResult(
        @Parameter(description = "결제 아이디") @PathVariable @Positive(message = OrderValidationMessage.PAYMENT_ID_POSITIVE) long paymentId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(orderPaymentResultInternalUseCase.getResult(paymentId)));
    }
}
