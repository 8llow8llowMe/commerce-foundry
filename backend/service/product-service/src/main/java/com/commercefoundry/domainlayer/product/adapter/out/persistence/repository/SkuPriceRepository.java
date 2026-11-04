package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuPriceEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuPriceRepository extends JpaRepository<SkuPriceEntity, Long> {
    List<SkuPriceEntity> findAllBySkuIdInOrderBySkuIdAscAdditionalPriceCurrencyAsc(Collection<Long> skuIds);
}
