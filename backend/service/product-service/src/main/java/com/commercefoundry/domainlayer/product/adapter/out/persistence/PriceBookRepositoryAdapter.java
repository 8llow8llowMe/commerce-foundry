package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductPriceRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuPriceRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductPriceEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuPriceEntity;
import com.commercefoundry.domainlayer.product.application.mapper.CurrencyPriceMapper;
import com.commercefoundry.domainlayer.product.application.port.out.PriceBookRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 통화별 가격 벌크 저장소. 호출자 TX를 강제하며 선행 부모 상품 CAS/@Version 보호는 writer의 책임이다. */
@Component
@RequiredArgsConstructor
public class PriceBookRepositoryAdapter implements PriceBookRepositoryPort {
    private final ProductPriceRepository products;
    private final SkuPriceRepository skus;
    private final CurrencyPriceMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<ProductCurrencyPrice> findProductPrices(Collection<Long> productIds) {
        return productIds.isEmpty() ? List.of() : products.findAllByProductIdInOrderByProductIdAscSalePriceCurrencyAsc(productIds).stream()
            .map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuCurrencyPrice> findSkuPrices(Collection<Long> skuIds) {
        return skuIds.isEmpty() ? List.of() : skus.findAllBySkuIdInOrderBySkuIdAscAdditionalPriceCurrencyAsc(skuIds).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertAll(Collection<ProductCurrencyPrice> productPrices, Collection<SkuCurrencyPrice> skuPrices) {
        products.saveAll(productPrices.stream().map(mapper::toEntity).toList());
        skus.saveAll(skuPrices.stream().map(mapper::toEntity).toList());
        if (!productPrices.isEmpty() || !skuPrices.isEmpty()) {
            products.flush();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateAll(Collection<ProductCurrencyPrice> productPrices, Collection<SkuCurrencyPrice> skuPrices) {
        var productInputs = productPrices.stream().collect(Collectors.toMap(ProductCurrencyPrice::id, Function.identity()));
        var skuInputs = skuPrices.stream().collect(Collectors.toMap(SkuCurrencyPrice::id, Function.identity()));
        var productRows = productInputs.isEmpty() ? List.<ProductPriceEntity>of()
            : products.findAllById(productInputs.keySet());
        var skuRows = skuInputs.isEmpty() ? List.<SkuPriceEntity>of()
            : skus.findAllById(skuInputs.keySet());
        if (productRows.size() != productInputs.size() || skuRows.size() != skuInputs.size()) {
            throw new IllegalStateException("수정할 가격행이 없습니다");
        }
        // 전체 identity를 먼저 검사해 일부 managed 행만 변경하는 상태를 만들지 않는다.
        productRows.forEach(row -> row.requireIdentity(productInputs.get(row.getId())));
        skuRows.forEach(row -> row.requireIdentity(skuInputs.get(row.getId())));
        productRows.forEach(row -> row.replaceAmounts(productInputs.get(row.getId())));
        skuRows.forEach(row -> row.replaceAmount(skuInputs.get(row.getId())));
        if (!productRows.isEmpty() || !skuRows.isEmpty()) {
            products.flush();
        }
    }
}
