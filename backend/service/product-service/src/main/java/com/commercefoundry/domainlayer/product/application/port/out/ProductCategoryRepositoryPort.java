package com.commercefoundry.domainlayer.product.application.port.out;

/**
 * 상품 ↔ 카테고리 연결(대표 카테고리 {@code product.category_id} · 진열 카테고리 {@code product_category}). 쓰기는 어드민 상품 등록 · 수정(#9 PR-E)에서 더한다.
 *
 * <p><b>PR-E · PR-D 선행 조건</b>
 * <ul>
 *   <li>카테고리 삭제 판정은 카테고리 쓰기끼리만 직렬화된다(카테고리 표 전체 잠금). 상품 쓰기(PR-E)가 카테고리를 참조할 때는 같은 잠금(또는 {@code FOR SHARE})으로
 *       존재를 확인해야 한다 — DB FK 가 없어, 삭제 판정 직후 연결하면 지워진 카테고리를 가리키는 상품이 생긴다.</li>
 *   <li>상품 soft delete(PR-D, {@code deleted_at}) 뒤에는 {@link #existsProductInCategory} 에 "삭제되지 않은 상품" 조건을 더한다 — 지금은 모든 상품을 센다.</li>
 * </ul>
 */
public interface ProductCategoryRepositoryPort {

    /**
     * 카테고리를 대표 또는 진열 카테고리로 쓰는 상품이 하나라도 있는가 — 숨김 · 판매 종료 상품도 센다.
     * 대표 카테고리는 진열 카테고리에도 들어 있어야 하지만(쓰기 불변식), 이관 데이터가 어겨도 놓치지 않게 둘 다 본다.
     */
    boolean existsProductInCategory(long categoryId);
}
