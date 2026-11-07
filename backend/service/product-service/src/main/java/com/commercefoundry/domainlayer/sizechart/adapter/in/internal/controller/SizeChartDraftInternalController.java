package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
public class SizeChartDraftInternalController {
    private final SizeChartDraftInternalUseCase useCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping("/internal/v1/size-chart-drafts/{draftId}/generation-input")
    public ResponseEntity<Response<SizeChartGenerationInputResponse>> generationInput(@PathVariable long draftId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.generationInput(draftId)));
    }
}
