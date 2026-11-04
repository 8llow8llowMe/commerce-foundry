package com.commercefoundry.domainlayer.notification.adapter.out.client.feign;

import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.JsonNode;

@FeignClient(name = "${feign-client.target-services.payment-service:payment-service}", contextId = "paymentAnomalyObservationClient",
    configuration = InternalFeignClientConfiguration.class)
public interface PaymentAnomalyObservationClient {
    @GetMapping("/internal/v1/payment-observations/anomalies")
    JsonNode anomalies();
}
