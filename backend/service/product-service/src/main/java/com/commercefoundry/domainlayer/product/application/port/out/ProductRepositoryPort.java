package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Slice;

public interface ProductRepositoryPort {

    /** 고객에게 보이는 상품 목록. 판매 종료 상품도 포함한다 — 숨김 상품만 뺀다. 카테고리 조건이 빈 집합이면 쿼리 없이 빈 Slice 다. */
    Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria);

    /** 고객에게 보이는 상품 한 건. 숨김 상품은 없는 것으로 본다. */
    Optional<Product> findVisibleById(long productId);

    /**
     * 숨김 상품까지 포함해 아이디 목록으로 읽는다. 빈 컬렉션이면 쿼리 없이 빈 목록이다.
     *
     * <p><b>고객 흐름에서 쓰지 않는다 — internal · 어드민 전용이다.</b> 고객 조회는 노출 조건이 붙은 {@link #searchVisible} ·
     * {@link #findVisibleById} 만 쓴다. 서비스 간 SKU 조회처럼 숨김 상품도 읽어야 하는 쪽은 노출 · 판매 상태 검증을 호출한 쪽이 한다.
     */
    List<Product> findAllByIdsIncludingHidden(Collection<Long> productIds);
}
