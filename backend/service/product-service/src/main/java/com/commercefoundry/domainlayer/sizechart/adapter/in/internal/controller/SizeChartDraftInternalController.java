package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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

    /**
     * 승인 이미지 원본 바이트(봉투 없음). 성공만 {@code application/octet-stream} 이고 오류는 기존 JSON 봉투다 —
     * {@code produces} 를 걸지 않아 오류 응답의 형식 협상이 막히지 않는다. 바뀐 이미지는 410 {@code SIZE_CHART_DRAFT_016}(재시도 불가)이다.
     */
    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping("/internal/v1/size-chart-drafts/{draftId}/generation-images/{index}")
    public ResponseEntity<byte[]> generationImage(@PathVariable long draftId, @PathVariable int index) {
        byte[] bytes = useCase.generationImage(draftId, index);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(bytes.length).header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
