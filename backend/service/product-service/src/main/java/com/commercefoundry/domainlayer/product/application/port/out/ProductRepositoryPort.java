package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductAdminListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;

public interface ProductRepositoryPort {

    /** 고객에게 보이는 상품 목록. 판매 종료 상품도 포함한다 — 숨김 · 삭제 상품만 뺀다. 카테고리 조건이 빈 집합이면 쿼리 없이 빈 Slice 다. */
    Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria);

    /**
     * 고객에게 보이는 상품의 목록 행을 아이디 목록으로 읽는다 — 숨김 · 삭제 상품 · 없는 아이디는 빠진다. 순서는 아이디 오름차순이다.
     * 빈 컬렉션이면 쿼리 없이 빈 목록이다. 같은 서비스의 전시 컨텍스트가 상품 카드를 만들 때 쓴다 ({@code ProductListProcessor#summarizeVisible}).
     */
    List<ProductListQueryResult> findVisibleListRowsByIds(Collection<Long> productIds);

    /** 고객에게 보이는 상품 한 건. 숨김 · 삭제 상품은 없는 것으로 본다. */
    Optional<Product> findVisibleById(long productId);

    /**
     * 숨김 · 삭제(soft delete) 상품까지 포함해 아이디 목록으로 읽는다. 빈 컬렉션이면 쿼리 없이 빈 목록이다.
     *
     * <p><b>고객 흐름에서 쓰지 않는다 — internal · 어드민 전용이다.</b> 고객 조회는 노출 조건이 붙은 {@link #searchVisible} ·
     * {@link #findVisibleById} 만 쓴다. 판매 가능 판정은 호출한 쪽이 {@code SaleAvailability#isOpenForSale} 로 한다 — 노출 · 판매 상태 · 삭제를 함께 본다.
     *
     * <p><b>삭제 상품을 거르지 않는다.</b> 서비스 간 SKU 조회는 삭제 상품의 SKU 도 행을 내리고 {@code purchasable=false} · 판매 종료로 알린다 —
     * 행을 빼면 order-service 가 장바구니 줄의 이름 · 가격을 잃고 "없는 SKU" 로 다룬다(계약 변경).
     */
    List<Product> findAllByIdsIncludingHidden(Collection<Long> productIds);

    /**
     * 어드민 상품 목록 — 숨김 상품을 포함하고 삭제 상품은 뺀다. 전체 건수를 센다. <b>고객 흐름에서 쓰지 않는다.</b>
     * 카테고리 조건이 빈 집합이면 쿼리 없이 빈 Page(전체 0건)다.
     */
    Page<ProductAdminListQueryResult> searchForAdmin(ProductAdminSearchCriteria criteria);
}
