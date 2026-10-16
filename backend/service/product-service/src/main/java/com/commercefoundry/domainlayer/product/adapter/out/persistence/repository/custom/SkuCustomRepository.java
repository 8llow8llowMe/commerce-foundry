package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import java.util.Collection;
import java.util.List;

public interface SkuCustomRepository {

    List<SkuAggregateQueryResult> aggregateByProductIds(Collection<Long> productIds);
}
