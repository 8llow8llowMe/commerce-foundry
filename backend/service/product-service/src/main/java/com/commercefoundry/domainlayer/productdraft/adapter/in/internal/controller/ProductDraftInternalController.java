package com.commercefoundry.domainlayer.productdraft.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraft.adapter.in.internal.dto.response.ProductDraftGenerationInputResponse;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * operations 생성 작업 전용 AI 상품 초안 입력 (#359 PR-2a, 선례: {@code SizeChartDraftInternalController}). 허용 호출자는 {@code SERVICE_operations-service} 뿐이다.
 * 응답은 캐시하지 않는다. 초안 오류는 {@code ProductDraftInternalExceptionHandler} 가 JSON 봉투로 고정한다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class ProductDraftInternalController {

    private final ProductDraftInternalUseCase productDraftInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping("/internal/v1/product-drafts/{draftId}/generation-input")
    public ResponseEntity<Response<ProductDraftGenerationInputResponse>> generationInput(@PathVariable long draftId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(productDraftInternalUseCase.generationInput(draftId)));
    }

    /**
     * 고정한 이미지 원본 바이트(봉투 없음). 성공만 {@code application/octet-stream} 이고 오류는 JSON 봉투다 — {@code produces} 를 걸지 않아 오류 응답의 형식 협상이 막히지 않는다.
     * 바뀐 이미지는 410 {@code PRODUCT_DRAFT_044}(재시도 불가), 순번 밖은 404 {@code 045} 다.
     */
    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping("/internal/v1/product-drafts/{draftId}/generation-images/{index}")
    public ResponseEntity<byte[]> generationImage(@PathVariable long draftId, @PathVariable int index) {
        byte[] bytes = productDraftInternalUseCase.generationImage(draftId, index);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(bytes.length).header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
