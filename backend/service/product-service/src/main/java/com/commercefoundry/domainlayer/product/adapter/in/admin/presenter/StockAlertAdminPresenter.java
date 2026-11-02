package com.commercefoundry.domainlayer.product.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAlertAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAlertPolicyResponse;
import com.commercefoundry.domainlayer.product.application.info.StockAlertInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class StockAlertAdminPresenter {

    public StockAlertPolicyResponse toPolicyResponse(StockAlertInfo.Policy policy) {
        return new StockAlertPolicyResponse(String.valueOf(policy.skuId()), policy.enabled(), policy.thresholdQuantity(), policy.updatedAt());
    }

    public PageResponse<StockAlertAdminItem> toPageResponse(Page<StockAlertInfo.LowStock> stocks) {
        return PageResponse.of(stocks).map(stock -> new StockAlertAdminItem(
            String.valueOf(stock.skuId()), String.valueOf(stock.productId()), stock.productName(), stock.optionValueKey(),
            stock.availableQuantity(), stock.thresholdQuantity(), stock.productVisible(), stock.productSaleStatus().toMetadata(),
            stock.skuSaleStatus().toMetadata(), stock.policyUpdatedAt()
        ));
    }
}
