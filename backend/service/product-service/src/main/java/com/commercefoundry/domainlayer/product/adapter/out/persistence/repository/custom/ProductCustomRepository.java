package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductAdminListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;

public interface ProductCustomRepository {

    Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria);

    List<ProductListQueryResult> findVisibleListRowsByIds(Collection<Long> productIds);

    Optional<ProductEntity> findVisibleById(long productId);

    Page<ProductAdminListQueryResult> searchForAdmin(ProductAdminSearchCriteria criteria);

    boolean existsLiveProductInCategory(long categoryId);

    boolean existsPurchasableProduct();

    Optional<Instant> findFirstRegisteredAt(long memberId);
}
