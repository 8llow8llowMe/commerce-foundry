package com.commercefoundry.domainlayer.product.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAdjustmentHistoryAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminListResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.application.info.StockAdjustmentHistoryInfo;
import com.commercefoundry.domainlayer.product.application.info.StockAdminInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class StockAdminPresenter {

    public StockAdminResponse toResponse(StockAdminInfo stock) {
        return StockAdminResponse.builder()
            .skuId(String.valueOf(stock.skuId()))
            .stockManaged(stock.stockManaged())
            .availableQuantity(stock.availableQuantity())
            .reservedQuantity(stock.reservedQuantity())
            .updatedAt(stock.updatedAt())
            .build();
    }

    public StockAdminListResponse toListResponse(List<StockAdminInfo> stocks) {
        return StockAdminListResponse.builder().stocks(stocks.stream().map(this::toResponse).toList()).build();
    }

    /** 페이지 정보(전체 건수 포함)는 그대로 두고 항목만 바꾼다. */
    public PageResponse<StockAdjustmentHistoryAdminItem> toAdjustmentPageResponse(Page<StockAdjustmentHistoryInfo> histories) {
        return PageResponse.of(histories).map(this::toAdjustmentItem);
    }

    private StockAdjustmentHistoryAdminItem toAdjustmentItem(StockAdjustmentHistoryInfo history) {
        return StockAdjustmentHistoryAdminItem.builder()
            .id(String.valueOf(history.id()))
            .beforeQuantity(history.beforeQuantity())
            .afterQuantity(history.afterQuantity())
            .delta(history.delta())
            .beforeStockManaged(history.beforeStockManaged())
            .afterStockManaged(history.afterStockManaged())
            .reason(history.reason().toMetadata())
            .memo(history.memo())
            .adminMemberId(String.valueOf(history.adminMemberId()))
            .createdAt(history.createdAt())
            .build();
    }
}
