package com.commercefoundry.domainlayer.notification.adapter.out.client.feign;

import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;

/** product 품절 임박 커서 조회(#229 PR-2). 응답은 원문 JSON 으로 받아 support 가 서킷 밖에서 필요한 칸만 엄격히 읽는다. */
@FeignClient(name = "${feign-client.target-services.product-service:product-service}", contextId = "lowStockObservationClient",
    configuration = InternalFeignClientConfiguration.class)
public interface LowStockObservationClient {
    @GetMapping("/internal/v1/stocks/low")
    JsonNode lowStocks(@RequestParam("afterSkuId") long afterSkuId, @RequestParam("size") int size);
}
