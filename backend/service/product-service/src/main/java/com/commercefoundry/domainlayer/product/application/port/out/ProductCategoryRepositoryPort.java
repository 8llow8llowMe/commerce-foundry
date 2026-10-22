package com.commercefoundry.domainlayer.product.application.port.out;

/**
 * 상품 ↔ 카테고리 연결(대표 카테고리 {@code product.category_id} · 진열 카테고리 {@code product_category}). 쓰기는 어드민 상품 등록 · 수정(#9 PR-E)에서 더한다.
 *
 * <p><b>PR-E 선행 조건</b> — 카테고리 삭제 판정은 카테고리 쓰기끼리만 직렬화된다(카테고리 표 전체 잠금). 상품 쓰기(PR-E)가 카테고리를 참조할 때는
 * 같은 잠금(또는 {@code FOR SHARE})으로 존재를 확인해야 한다 — DB FK 가 없어, 삭제 판정 직후 연결하면 지워진 카테고리를 가리키는 상품이 생긴다.
 */
public interface ProductCategoryRepositoryPort {

    /**
     * 카테고리를 대표 또는 진열 카테고리로 쓰는 <b>삭제되지 않은</b> 상품이 하나라도 있는가 — 숨김 · 판매 종료 상품은 센다.
     * 대표 카테고리는 진열 카테고리에도 들어 있어야 하지만(쓰기 불변식), 이관 데이터가 어겨도 놓치지 않게 둘 다 본다.
     *
     * <p>삭제(soft delete) 상품만 연결된 카테고리는 지울 수 있다 — 그 뒤 삭제 상품의 {@code category_id} · 진열 행은 없는 카테고리를 가리킬 수 있다.
     * 삭제 상품은 고객 · 어드민 목록에 나오지 않고 v1 에 복구가 없어 받아들인다.
     */
    boolean existsProductInCategory(long categoryId);
}
