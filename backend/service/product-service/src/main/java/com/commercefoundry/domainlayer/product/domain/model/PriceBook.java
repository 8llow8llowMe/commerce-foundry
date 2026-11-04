package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 한 상품의 모든 가격행과 전체 legacy SKU 상태를 검증하는 불변 스냅숏이다.
 * 재고 수량은 입력받지 않는다. 품절도 ON_SALE 집합의 zero 조건에 포함한다.
 * 추가 통화 지원 SKU가 전혀 없는 상태의 허용 정책은 B2에서 별도 합의한다.
 */
public record PriceBook(long productId, CurrencyCode baseCurrency, Map<Long, SaleStatus> skuStatuses,
                        List<ProductCurrencyPrice> productPrices, List<SkuCurrencyPrice> skuPrices) {
    public PriceBook {
        if (productId <= 0) {
            throw new IllegalArgumentException("상품 식별자는 양수여야 합니다");
        }
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        skuStatuses = Map.copyOf(skuStatuses);
        productPrices = List.copyOf(productPrices);
        skuPrices = List.copyOf(skuPrices);
        if (skuStatuses.keySet().stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("SKU 식별자는 양수여야 합니다");
        }
        Set<Long> productRowIds = new HashSet<>();
        Map<CurrencyCode, ProductCurrencyPrice> byCurrency = new java.util.EnumMap<>(CurrencyCode.class);
        for (var price : productPrices) {
            if (price.productId() != productId || !productRowIds.add(price.id())
                || byCurrency.putIfAbsent(price.salePrice().currency(), price) != null) {
                throw new IllegalArgumentException("상품 가격 소속 또는 식별자가 중복·불일치합니다");
            }
        }
        if (!byCurrency.containsKey(baseCurrency)) {
            throw new IllegalArgumentException("기준통화 상품 가격은 필수입니다");
        }
        Set<Long> skuRowIds = new HashSet<>();
        Set<String> logicalKeys = new HashSet<>();
        Set<Long> baseSkuIds = new HashSet<>();
        for (var price : skuPrices) {
            CurrencyCode currency = price.additionalPrice().currency();
            if (!skuStatuses.containsKey(price.skuId()) || !byCurrency.containsKey(currency)
                || !skuRowIds.add(price.id()) || !logicalKeys.add(price.skuId() + ":" + currency)) {
                throw new IllegalArgumentException("SKU 가격 소속·부모 가격 또는 식별자가 중복·불일치합니다");
            }
            ProductPricing.skuSalePrice(byCurrency.get(currency).salePrice(), price.additionalPrice());
            if (currency == baseCurrency) {
                baseSkuIds.add(price.skuId());
            }
        }
        if (!baseSkuIds.equals(skuStatuses.keySet())) {
            throw new IllegalArgumentException("판매 중지·은퇴를 포함한 모든 기준통화 SKU 가격은 필수입니다");
        }
        Map<Long, SaleStatus> statuses = skuStatuses;
        Map<CurrencyCode, List<SkuCurrencyPrice>> grouped = skuPrices.stream()
            .collect(Collectors.groupingBy(price -> price.additionalPrice().currency()));
        for (var price : productPrices) {
            var supported = grouped.getOrDefault(price.salePrice().currency(), List.of()).stream()
                .map(sku -> new ProductPricing.SkuPrice(sku.additionalPrice(), statuses.get(sku.skuId()))).toList();
            ProductPricing.violation(price.salePrice(), price.listPrice(), supported).ifPresent(reason -> {
                throw new IllegalArgumentException(reason);
            });
        }
    }

    /** 가격 부재는 Optional.empty이며 실제 0 가격과 구분한다. */
    public Optional<Money> skuSalePrice(long skuId, CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency");
        var additional = skuPrices.stream().filter(price -> price.skuId() == skuId && price.additionalPrice().currency() == currency).findFirst();
        if (additional.isEmpty()) {
            return Optional.empty();
        }
        var base = productPrices.stream().filter(price -> price.salePrice().currency() == currency).findFirst().orElseThrow();
        return Optional.of(ProductPricing.skuSalePrice(base.salePrice(), additional.orElseThrow().additionalPrice()));
    }
}
