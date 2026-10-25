package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import java.util.Collection;
import java.util.List;

public interface ProductCustomOrderFieldRepositoryPort {

    /** 노출 순서 → 아이디 오름차순. */
    List<CustomOrderField> findAllByProductId(long productId);

    /**
     * 여러 상품의 입력 항목. 상품 아이디 → 노출 순서 → 아이디 오름차순.
     * 빈 컬렉션이면 쿼리하지 않는다.
     */
    List<CustomOrderField> findAllByProductIds(Collection<Long> productIds);
}
