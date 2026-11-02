package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import java.time.Instant;

public final class StockAlertInfo {

    public record Policy(long skuId, boolean enabled, Long thresholdQuantity, Instant updatedAt) {
    }

    public record LowStock(
        long skuId, long productId, String productName, String optionValueKey, long availableQuantity, long thresholdQuantity,
        boolean productVisible, SaleStatus productSaleStatus, SaleStatus skuSaleStatus, Instant policyUpdatedAt
    ) {
    }

    private StockAlertInfo() {
    }
}
