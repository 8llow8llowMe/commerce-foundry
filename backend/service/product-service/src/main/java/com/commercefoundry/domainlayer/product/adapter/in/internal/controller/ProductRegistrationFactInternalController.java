package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.ProductRegistrationFactInternalResponse;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.ProductRegistrationFactInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/onboarding-facts")
public class ProductRegistrationFactInternalController {
    private final ProductRegistrationFactInternalUseCase useCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping("/product-registration")
    public ResponseEntity<Response<ProductRegistrationFactInternalResponse>> getFact(
        @RequestParam @Positive(message = ProductValidationMessage.ONBOARDING_MEMBER_ID_POSITIVE) long memberId
    ) {
        return ResponseEntity.ok(Response.success(useCase.getFact(memberId)));
    }
}
