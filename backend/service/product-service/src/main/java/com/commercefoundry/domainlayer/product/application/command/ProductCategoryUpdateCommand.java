package com.commercefoundry.domainlayer.product.application.command;

import java.util.List;

/** 대표 · 진열 카테고리 전체 교체. */
public record ProductCategoryUpdateCommand(long productId, long version, long actorMemberId, long categoryId, List<Long> categoryIds) {
}
