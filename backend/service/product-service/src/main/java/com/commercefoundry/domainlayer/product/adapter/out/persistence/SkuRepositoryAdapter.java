package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.application.mapper.SkuMapper;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
    public List<Long> findExistingIds(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return skuRepository.findExistingIds(skuIds);
    }

    @Override
    public Optional<Sku> findByIdForUpdate(long skuId) {
        return skuRepository.findByIdForUpdate(skuId).map(skuMapper::toDomain);
    }

    @Override
    public List<SkuAggregateQueryResult> aggregateByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return skuRepository.aggregateByProductIds(productIds);
    }

    /** 엔티티가 {@code Persistable} 이라 새 행은 persist(INSERT) 로 간다 — 같은 조합이면 {@code uk_sku_product_id_option_value_key} 위반이다. */
    @Override
    public void insertAll(List<Sku> skus) {
        skuRepository.saveAll(skus.stream()
            .map(sku -> SkuEntity.builder()
                .id(sku.id())
                .productId(sku.productId())
                .optionValueKey(sku.optionKey().value())
                .additionalPriceAmount(sku.additionalPrice().amount())
                .additionalPriceCurrency(sku.additionalPrice().currency())
                .saleStatus(sku.saleStatus())
                .weightGrams(sku.weightGrams())
                .hsCode(sku.hsCode() == null ? null : sku.hsCode().value())
                .build())
            .toList());
    }
}
