package com.commercefoundry.domainlayer.product.application.port.out;

import java.util.List;

/**
 * 상품 ↔ 카테고리 연결(대표 카테고리 {@code product.category_id} · 진열 카테고리 {@code product_category}). 쓰기는 어드민 상품 등록(#9 E1) · 수정(E2)이다.
 *
 * <p><b>카테고리 잠금</b> — 카테고리 삭제 판정은 카테고리 쓰기끼리만 직렬화된다(카테고리 표 전체 잠금). 상품 쓰기가 카테고리를 참조할 때는
 * 같은 잠금으로 존재를 확인한다(등록은 {@code ProductAdminFacade#create}) — DB FK 가 없어, 삭제 판정 직후 연결하면 지워진 카테고리를 가리키는 상품이 생긴다.
 */
public interface ProductCategoryRepositoryPort {

    /** 저장된 진열 카테고리 아이디를 오름차순으로 읽는다. 대표 카테고리 보완은 호출자가 한다. */
    List<Long> findCategoryIdsByProductId(long productId);

    /**
     * 카테고리를 대표 또는 진열 카테고리로 쓰는 <b>삭제되지 않은</b> 상품이 하나라도 있는가 — 숨김 · 판매 종료 상품은 센다.
     * 대표 카테고리는 진열 카테고리에도 들어 있어야 하지만(쓰기 불변식), 이관 데이터가 어겨도 놓치지 않게 둘 다 본다.
     *
     * <p>삭제(soft delete) 상품만 연결된 카테고리는 지울 수 있다 — 그 뒤 삭제 상품의 {@code category_id} · 진열 행은 없는 카테고리를 가리킬 수 있다.
     * 삭제 상품은 고객 · 어드민 목록에 나오지 않고 v1 에 복구가 없어 받아들인다.
     */
    boolean existsProductInCategory(long categoryId);

    /**
     * 진열 카테고리 연결 INSERT — 새 상품용(#9 E1). 대표 카테고리가 들어 있어야 한다(쓰기 불변식, 호출한 쪽이 보장).
     * 카테고리 안 진열 순서({@code display_order})는 0 으로 넣는다 — 편집 API 가 없고, 상품 쪽 배열 순번과 뜻이 다르다.
     *
     * @param ids 연결 행 아이디 — {@code categoryIds} 와 같은 길이, 호출한 쪽이 발급했다
     */
    void insertLinks(long productId, List<Long> categoryIds, List<Long> ids);

    /** 제거된 연결만 삭제한다. 유지된 행의 아이디 · 생성 시각 · 진열 순서는 보존한다. */
    void deleteLinks(long productId, List<Long> categoryIds);
}
