package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import java.util.Collection;
import java.util.List;

/**
 * SKU. 컬렉션 인자 조회는 빈 컬렉션이면 쿼리 없이 빈 목록이다.
 */
public interface SkuRepositoryPort {

    /** 아이디 오름차순. */
    List<Sku> findAllByProductId(long productId);

    List<Sku> findAllByIds(Collection<Long> skuIds);

    boolean existsById(long skuId);

    /** 상품별 SKU 집계. SKU 가 없는 상품은 결과에 행이 없다. */
    List<SkuAggregateQueryResult> aggregateByProductIds(Collection<Long> productIds);
}
