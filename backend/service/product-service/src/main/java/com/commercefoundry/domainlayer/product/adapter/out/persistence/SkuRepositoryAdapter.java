package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.application.mapper.SkuMapper;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SkuRepositoryAdapter implements SkuRepositoryPort {

    private final SkuRepository skuRepository;
    private final SkuMapper skuMapper;

    @Override
    public List<Sku> findAllByProductId(long productId) {
        return skuMapper.toDomains(skuRepository.findAllByProductIdOrderByIdAsc(productId));
    }

    @Override
    public List<Sku> findAllByIds(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return skuMapper.toDomains(skuRepository.findAllById(skuIds));
    }

    @Override
    public boolean existsById(long skuId) {
        return skuRepository.existsById(skuId);
    }

    @Override
    public List<SkuAggregateQueryResult> aggregateByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return skuRepository.aggregateByProductIds(productIds);
    }
}
