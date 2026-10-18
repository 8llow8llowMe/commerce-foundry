package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;

public interface StockAdminUseCase {

    /**
     * 어드민 재고 조정. 없는 SKU 는 {@code PRODUCT_002}(404), 기대 수량이 현재 수량과 다르면 {@code PRODUCT_020}(409) 이다.
     * 요청 한 번에 조정 이력 한 건을 남긴다.
     */
    StockAdminResponse adjustStock(StockAdjustmentCommand command);
}
