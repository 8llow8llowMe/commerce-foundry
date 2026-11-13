package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.feign.dto.ProductDraftGenerationInputInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * product AI 상품 초안 생성 입력 internal (#359 PR-2a, 허용 호출자 {@code SERVICE_operations-service}). 서킷 · 예외 변환은
 * {@code ProductDraftGenerationInputInternalResponseSupport} 가 한다(서킷 {@code product-service}). 읽기 타임아웃은 yml 의 이 contextId 설정이다 —
 * 이미지 한 장이 최대 5MiB 라 기본 3초보다 길다.
 */
@FeignClient(name = "${feign-client.target-services.product-service:product-service}",
    contextId = "productDraftGenerationInputClient", configuration = InternalFeignClientConfiguration.class)
public interface ProductDraftGenerationInputClient {

    @GetMapping("/internal/v1/product-drafts/{draftId}/generation-input")
    Response<ProductDraftGenerationInputInternalResponse> getGenerationInput(@PathVariable("draftId") long draftId);

    /**
     * 고정한 이미지 원본 바이트(봉투 없음, {@code application/octet-stream}). 원시 {@link feign.Response} 로 받는다 — 헤더를 보고 본문을 상한까지만 읽으려고.
     * 이 반환형은 Feign 이 상태 코드로 오류를 디코딩하지 않으므로(ErrorDecoder 를 지나지 않는다) 어댑터가 상태 · 헤더 · 스트림을 모두 보고 닫는다.
     */
    @GetMapping("/internal/v1/product-drafts/{draftId}/generation-images/{index}")
    feign.Response getGenerationImage(@PathVariable("draftId") long draftId, @PathVariable("index") int index);
}
