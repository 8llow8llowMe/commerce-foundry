package com.commercefoundry.domainlayer.picking.adapter.out.client;

import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.SkuBarcodeClient;
import com.commercefoundry.domainlayer.picking.adapter.out.client.support.SkuBarcodeInternalResponseSupport;
import com.commercefoundry.domainlayer.picking.application.port.out.SkuBarcodeQueryPort;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SkuBarcodeClientAdapter implements SkuBarcodeQueryPort {
    private final SkuBarcodeClient client;
    private final SkuBarcodeInternalResponseSupport responseSupport;

    @Override
    public Map<String, Long> resolve(Set<String> barcodes) {
        return responseSupport.read(barcodes, () -> client.resolve(new SkuBarcodeClient.Search(barcodes)));
    }
}
