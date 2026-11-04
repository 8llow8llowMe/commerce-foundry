package com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.dto.response.PaymentObservationResponse;
import com.commercefoundry.domainlayer.paymentobservation.application.port.in.PaymentObservationInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/payment-observations")
public class PaymentObservationInternalController {

    private final PaymentObservationInternalUseCase useCase;

    @GetMapping("/anomalies")
    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    public ResponseEntity<Response<PaymentObservationResponse>> observeAnomalies() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.observeAnomalies()));
    }
}
