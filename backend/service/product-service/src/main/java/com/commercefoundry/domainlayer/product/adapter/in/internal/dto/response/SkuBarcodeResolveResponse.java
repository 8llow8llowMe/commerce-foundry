package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response;

import java.util.Map;

/** 등록된 바코드와 SKU 식별자. 미등록 바코드는 생략한다. */
public record SkuBarcodeResolveResponse(Map<String, String> skuIdsByBarcode) {
}
