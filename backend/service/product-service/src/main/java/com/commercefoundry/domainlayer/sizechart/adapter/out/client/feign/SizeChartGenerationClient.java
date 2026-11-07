package com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationRegisterRequest;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationResultResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "${feign-client.target-services.operations-service:operations-service}", contextId = "sizeChartGenerationClient",
    configuration = {InternalFeignClientConfiguration.class, SizeChartGenerationFeignConfiguration.class})
public interface SizeChartGenerationClient {
    @PostMapping("/internal/v1/size-chart-generation-jobs")
    Response<SizeChartGenerationRegistrationResponse> register(@RequestBody SizeChartGenerationRegisterRequest request);

    @GetMapping("/internal/v1/size-chart-generation-jobs/{jobId}/result")
    Response<SizeChartGenerationResultResponse> result(@PathVariable("jobId") long jobId);
}
