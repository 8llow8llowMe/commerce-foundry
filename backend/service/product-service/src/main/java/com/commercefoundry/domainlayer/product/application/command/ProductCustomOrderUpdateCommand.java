package com.commercefoundry.domainlayer.product.application.command;

/** {@code PUT /api/v1/admin/products/{productId}/custom-order} — 상세 조회 버전과 맞춤 제작 설정 전체 (#9 PR-B). */
public record ProductCustomOrderUpdateCommand(long productId, long version, long actorMemberId, ProductCustomOrderCommand settings) {
}
