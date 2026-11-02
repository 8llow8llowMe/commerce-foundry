package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAlertAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAlertPolicyResponse;
import com.commercefoundry.persistence.dto.PageResponse;

public interface StockAlertAdminUseCase {

    StockAlertPolicyResponse getPolicy(long skuId);

    StockAlertPolicyResponse setPolicy(long skuId, long thresholdQuantity, long actorMemberId);

    void clearPolicy(long skuId, long actorMemberId);

    PageResponse<StockAlertAdminItem> getLowStocks(int page, int size);
}
