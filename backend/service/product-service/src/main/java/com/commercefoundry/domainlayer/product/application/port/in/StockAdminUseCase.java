package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAdjustmentHistoryAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminListResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;

public interface StockAdminUseCase {

    /**
     * SKU 재고 목록. 요청 순서(중복 제거 후)이고 없는 SKU 는 빠진다. 재고 행이 없는 SKU 는 품절(관리 true · 수량 0 · 수정 시각 null)이다.
     */
    StockAdminListResponse getStocks(List<Long> skuIds);

    /** SKU 하나의 조정 이력 — 최신순 페이지. 없는 SKU 는 {@code PRODUCT_002}(404) 다. */
    PageResponse<StockAdjustmentHistoryAdminItem> getAdjustments(long skuId, int page, int size);

    /**
     * 어드민 재고 조정. 없는 SKU 는 {@code PRODUCT_002}(404), 기대 수량이 현재 수량과 다르면 {@code PRODUCT_020}(409) 이다.
     * 요청 한 번에 조정 이력 한 건을 남긴다.
     */
    StockAdminResponse adjustStock(StockAdjustmentCommand command);
}
