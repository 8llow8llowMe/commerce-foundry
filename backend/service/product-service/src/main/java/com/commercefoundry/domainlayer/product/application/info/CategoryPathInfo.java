package com.commercefoundry.domainlayer.product.application.info;

/**
 * 상세 화면 breadcrumb 한 칸 — 상품 대표 카테고리의 최상위 → 대상 경로 중 보이는 카테고리.
 */
public record CategoryPathInfo(long categoryId, String name) {
}
