package com.commercefoundry.domainlayer.product.application.port.out;

/**
 * 상품 ↔ 카테고리 연결(대표 카테고리 {@code product.category_id} · 진열 카테고리 {@code product_category}). 쓰기는 어드민 상품 등록 · 수정(#9 PR-E)에서 더한다.
 */
public interface ProductCategoryRepositoryPort {

    /**
     * 카테고리를 대표 또는 진열 카테고리로 쓰는 상품이 하나라도 있는가 — 숨김 · 판매 종료 상품도 센다.
     * 대표 카테고리는 진열 카테고리에도 들어 있어야 하지만(쓰기 불변식), 이관 데이터가 어겨도 놓치지 않게 둘 다 본다.
     */
    boolean existsProductInCategory(long categoryId);
}
