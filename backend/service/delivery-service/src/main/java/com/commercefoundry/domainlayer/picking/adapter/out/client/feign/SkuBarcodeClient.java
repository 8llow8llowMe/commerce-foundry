package com.commercefoundry.domainlayer.picking.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import java.util.Map;
import java.util.Set;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
    name = "${feign-client.target-services.product-service:product-service}",
    contextId = "skuBarcodeClient",
    configuration = {InternalFeignClientConfiguration.class, SkuBarcodeClientConfiguration.class}
)
public interface SkuBarcodeClient {
    @PostMapping("/internal/v1/skus/barcodes/resolve")
    Response<Resolved> resolve(@RequestBody Search request);

    record Search(Set<String> barcodes) { }

    record Resolved(Map<String, String> skuIdsByBarcode) { }
}
