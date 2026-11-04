package com.commercefoundry.domainlayer.payment.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentApprovalSummaryResponse;
import com.commercefoundry.domainlayer.payment.application.command.PaymentApprovalSummaryCommand;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentApprovalSummaryInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/payments/approval-summary")
public class PaymentApprovalSummaryInternalController {
    private final PaymentApprovalSummaryInternalUseCase useCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping
    public ResponseEntity<Response<PaymentApprovalSummaryResponse>> summarize(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.summarize(new PaymentApprovalSummaryCommand(from, to))));
    }
}
