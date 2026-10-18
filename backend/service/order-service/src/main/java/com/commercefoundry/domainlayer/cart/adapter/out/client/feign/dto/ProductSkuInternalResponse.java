package com.commercefoundry.domainlayer.cart.adapter.out.client.feign.dto;

import java.util.List;

/**
 * product-service {@code GET /internal/v1/skus} 응답 본문.
 *
 * @param skus 요청 순서(중복 제거 후). 없는 아이디는 빠진다
 */
public record ProductSkuInternalResponse(List<ProductSkuInternalItem> skus) {
}
