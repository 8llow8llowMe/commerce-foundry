package com.commercefoundry.domainlayer.product.application.port.out.query;

import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import java.util.List;

/** 타입화된 미투영 원천이다. 가격행 완전성·집합 불변식·legacy drift를 인증하지 않는다. */
public record PriceSourceSnapshotQueryResult(
    List<Long> productIds,
    List<Product> products,
    List<Sku> skus,
    List<ProductCurrencyPrice> productPrices,
    List<SkuCurrencyPrice> skuPrices
) {
    public PriceSourceSnapshotQueryResult {
        productIds = List.copyOf(productIds);
        products = List.copyOf(products);
        skus = List.copyOf(skus);
        productPrices = List.copyOf(productPrices);
        skuPrices = List.copyOf(skuPrices);
    }

    @Override
    public String toString() {
        return "PriceSourceSnapshotQueryResult[redacted]";
    }
}
