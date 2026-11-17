package com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.request.ProductDraftGenerationRegisterRequest;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationResultResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationValidationMessage;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.in.ProductDraftGenerationInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** product 전용 AI 상품 초안 생성 등록 · 결과 조회 internal (#359 PR-2b-2). 범용 작업 등록 API 가 아니다. */
@Hidden
@RestController
@RequestMapping("/internal/v1/product-draft-generation-jobs")
@PreAuthorize("hasAuthority('SERVICE_product-service')")
@RequiredArgsConstructor
public class ProductDraftGenerationInternalController {
    private final ProductDraftGenerationInternalUseCase useCase;

    @PostMapping
    public ResponseEntity<Response<ProductDraftGenerationRegistrationResponse>> register(@Valid @RequestBody ProductDraftGenerationRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(Response.success(useCase.register(request.toCommand())));
    }

    @GetMapping("/{jobId}/result")
    public ResponseEntity<Response<ProductDraftGenerationResultResponse>> result(
        @PathVariable @Positive(message = ProductDraftGenerationValidationMessage.JOB_ID) long jobId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.result(jobId)));
    }
}
