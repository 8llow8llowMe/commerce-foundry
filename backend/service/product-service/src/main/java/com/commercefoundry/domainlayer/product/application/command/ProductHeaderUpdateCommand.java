package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;

/** Header fields only. Child collections and prices are edited by separate operations. */
public record ProductHeaderUpdateCommand(
    long productId, long version, long actorMemberId, String name, String summary, String descriptionHtml,
    SaleStatus saleStatus, boolean visible
) {
}
