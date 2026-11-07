package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.dto.SizeChartGenerationInputInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "${feign-client.target-services.product-service:product-service}",
    contextId = "sizeChartGenerationInputClient", configuration = InternalFeignClientConfiguration.class)
public interface SizeChartGenerationInputClient {
    @GetMapping("/internal/v1/size-chart-drafts/{draftId}/generation-input")
    Response<SizeChartGenerationInputInternalResponse> getGenerationInput(@PathVariable("draftId") long draftId);
}
