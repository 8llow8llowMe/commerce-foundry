package com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign.dto.ProductRegistrationFactInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "${feign-client.target-services.product-service:product-service}",
    contextId = "productRegistrationFactClient", configuration = InternalFeignClientConfiguration.class)
public interface ProductRegistrationFactClient {
    @GetMapping("/internal/v1/onboarding-facts/product-registration")
    Response<ProductRegistrationFactInternalResponse> getProductRegistrationFact(@RequestParam("memberId") long memberId);
}
