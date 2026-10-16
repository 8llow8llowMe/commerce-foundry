package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Slice;

public interface ProductRepositoryPort {

    /** 고객에게 보이는 상품 목록. 판매 종료 상품도 포함한다 — 숨김 상품만 뺀다. */
    Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria);

    /** 고객에게 보이는 상품 한 건. 숨김 상품은 없는 것으로 본다. */
    Optional<Product> findVisibleById(long productId);

    /** 노출 여부와 무관하게 읽는다 (서비스 간 SKU 조회 — 주문 시점 검증은 호출한 쪽이 한다). 빈 컬렉션이면 쿼리 없이 빈 목록이다. */
    List<Product> findAllByIds(Collection<Long> productIds);
}
