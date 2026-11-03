package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;

public record SkuBulkUpdateCommand(long productId, long version, long actorMemberId, List<Item> skus) {
    public SkuBulkUpdateCommand {
        skus = List.copyOf(skus);
    }

    public record Item(long skuId, Money additionalPrice, SaleStatus saleStatus, Integer weightGrams, HsCode hsCode) {
    }
}
