package com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.dto.ProductDraftGenerationRegisterRequest;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.dto.ProductDraftGenerationRegistrationResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** operations 상품 초안 생성 작업 등록 internal (#359 PR-B1, {@code api-design-guide.md} §7-3). 서비스 토큰으로 부른다. */
@FeignClient(name = "${feign-client.target-services.operations-service:operations-service}", contextId = "productDraftGenerationClient",
    configuration = {InternalFeignClientConfiguration.class, ProductDraftGenerationFeignConfiguration.class})
public interface ProductDraftGenerationClient {

    @PostMapping("/internal/v1/product-draft-generation-jobs")
    Response<ProductDraftGenerationRegistrationResponse> register(@RequestBody ProductDraftGenerationRegisterRequest request);

    @GetMapping("/internal/v1/product-draft-generation-jobs/{jobId}/result")
    feign.Response result(@PathVariable("jobId") long jobId);
}
