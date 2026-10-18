package com.commercefoundry.domainlayer.product.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.application.info.StockAdminInfo;
import org.springframework.stereotype.Component;

@Component
public class StockAdminPresenter {

    public StockAdminResponse toResponse(StockAdminInfo stock) {
        return StockAdminResponse.builder()
            .skuId(String.valueOf(stock.skuId()))
            .stockManaged(stock.stockManaged())
            .availableQuantity(stock.availableQuantity())
            .updatedAt(stock.updatedAt())
            .build();
    }
}
