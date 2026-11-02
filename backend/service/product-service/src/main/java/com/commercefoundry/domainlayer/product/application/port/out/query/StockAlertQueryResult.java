package com.commercefoundry.domainlayer.product.application.port.out.query;

import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import java.time.Instant;

/** 어드민 품절 임박 목록 한 행. JOIN이 SKU당 한 행만 생성한다. */
public record StockAlertQueryResult(
    long skuId, long productId, String productName, String optionValueKey, long availableQuantity, long thresholdQuantity,
    boolean productVisible, SaleStatus productSaleStatus, SaleStatus skuSaleStatus, Instant policyUpdatedAt
) {
}
