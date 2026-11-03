package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.shared.commerce.Money;

/** 기본 판매가 · 정가 전체 교체. null 정가는 삭제다. */
public record ProductPricingUpdateCommand(long productId, long version, long actorMemberId, Money salePrice, Money listPrice) {
}
