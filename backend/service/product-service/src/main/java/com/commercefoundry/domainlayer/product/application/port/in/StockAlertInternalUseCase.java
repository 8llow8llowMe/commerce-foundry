package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockAlertScanInternalResponse;

public interface StockAlertInternalUseCase {
    StockAlertScanInternalResponse getLowStocks(long afterSkuId, int size);
}
