package com.commercefoundry.domainlayer.product.domain.model;

import java.util.HashMap;
import java.util.List;

/**
 * 실제 가격행으로 만든 미연결 기준통화 투영. legacy 상품의 통화 포인터와 전체 SKU를 사용한다.
 * PriceBook의 전체 통화 불변식을 재사용하며 drift 대조·보정·저장·원격 조회는 하지 않는다.
 */
public final class BaseCurrencyPriceProjection {
    private final Product product;
    private final List<Sku> skus;
    private final PriceBook priceBook;

    private BaseCurrencyPriceProjection(Product product, List<Sku> skus, PriceBook priceBook) {
        this.product = product;
        this.skus = List.copyOf(skus);
        this.priceBook = priceBook;
    }

    public Product product() { return product; }
    public List<Sku> skus() { return skus; }
    public PriceBook priceBook() { return priceBook; }

    /** 전체 SKU에는 STOP/은퇴도 포함한다. 입력 SKU 순서와 비가격 필드를 그대로 보존한다. */
    public static BaseCurrencyPriceProjection project(Product legacy, List<Sku> skus,
                                                       List<ProductCurrencyPrice> productPrices, List<SkuCurrencyPrice> skuPrices) {
        try {
            var snapshot = LegacyPriceSnapshot.of(legacy, skus);
            var base = snapshot.salePrice().currency();
            var book = new PriceBook(legacy.id(), base, snapshot.skuStatuses(), productPrices, skuPrices);
            var productPrice = book.productPrices().stream().filter(row -> row.salePrice().currency() == base).findFirst().orElseThrow();
            var additionalBySku = new HashMap<Long, SkuCurrencyPrice>();
            for (var row : book.skuPrices()) {
                if (row.additionalPrice().currency() == base) {
                    additionalBySku.put(row.skuId(), row);
                }
            }
            var projectedSkus = snapshot.skus().stream().map(sku -> sku.withEditableProperties(
                additionalBySku.get(sku.id()).additionalPrice(), sku.saleStatus(), sku.weightGrams(), sku.hsCode())).toList();
            return new BaseCurrencyPriceProjection(legacy.withPricing(productPrice.salePrice(), productPrice.listPrice()), projectedSkus, book);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ArithmeticException exception) {
            throw new IllegalStateException("기준통화 가격 투영 입력이 올바르지 않습니다");
        }
    }

    @Override
    public String toString() {
        return "BaseCurrencyPriceProjection[redacted]";
    }
}
