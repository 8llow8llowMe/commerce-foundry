package com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.request.SizeChartGenerationRegisterRequest;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationResultResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationValidationMessage;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.in.SizeChartGenerationInternalUseCase;
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

@Hidden
@RestController
@RequestMapping("/internal/v1/size-chart-generation-jobs")
@PreAuthorize("hasAuthority('SERVICE_product-service')")
@RequiredArgsConstructor
public class SizeChartGenerationInternalController {
    private final SizeChartGenerationInternalUseCase useCase;

    @PostMapping
    public ResponseEntity<Response<SizeChartGenerationRegistrationResponse>> register(@Valid @RequestBody SizeChartGenerationRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(Response.success(useCase.register(request.toCommand())));
    }

    @GetMapping("/{jobId}/result")
    public ResponseEntity<Response<SizeChartGenerationResultResponse>> result(
        @PathVariable @Positive(message = SizeChartGenerationValidationMessage.JOB_ID) long jobId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.result(jobId)));
    }
}
