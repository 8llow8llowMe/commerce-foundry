package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import java.util.Optional;
import org.springframework.data.domain.Slice;

public interface ProductCustomRepository {

    Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria);

    Optional<ProductEntity> findVisibleById(long productId);
}
