package com.commercefoundry.domainlayer.product.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockAlertInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockAlertScanInternalResponse;
import com.commercefoundry.domainlayer.product.application.info.StockAlertInfo;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class StockAlertInternalPresenter {
    public StockAlertScanInternalResponse toResponse(Instant observedAt, StockAlertInfo.Scan scan) {
        var contents = scan.contents().stream().map(stock -> new StockAlertInternalItem(
            String.valueOf(stock.skuId()), String.valueOf(stock.productId()), stock.productName(), stock.optionValueKey(),
            stock.availableQuantity(), stock.thresholdQuantity(), stock.productVisible(), stock.productSaleStatus().toMetadata(),
            stock.skuSaleStatus().toMetadata(), stock.policyUpdatedAt()
        )).toList();
        String nextAfterSkuId = scan.nextAfterSkuId() == null ? null : String.valueOf(scan.nextAfterSkuId());
        return new StockAlertScanInternalResponse(observedAt, contents, scan.hasNext(), nextAfterSkuId);
    }
}
