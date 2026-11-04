package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductPriceEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceRepository extends JpaRepository<ProductPriceEntity, Long> {
    List<ProductPriceEntity> findAllByProductIdInOrderByProductIdAscSalePriceCurrencyAsc(Collection<Long> productIds);
}
