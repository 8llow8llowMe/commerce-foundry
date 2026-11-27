package com.commercefoundry.domainlayer.picking.application.port.out;

import java.util.Map;
import java.util.Set;

public interface SkuBarcodeQueryPort {
    Map<String, Long> resolve(Set<String> barcodes);
}
