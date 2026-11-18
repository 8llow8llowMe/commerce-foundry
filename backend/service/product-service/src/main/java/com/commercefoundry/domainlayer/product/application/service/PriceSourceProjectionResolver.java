package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.application.exception.PriceSourceProjectionException;
import com.commercefoundry.domainlayer.product.application.exception.PriceSourceProjectionException.Reason;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceSourceSnapshotQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.BaseCurrencyPriceProjection;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceDrift;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class PriceSourceProjectionResolver {
    private PriceSourceProjectionResolver() {
    }

    /** 전체 스냅숏을 검증한 뒤 요청 상품과 입력 SKU의 순서를 보존한 투영을 반환한다. */
    public static List<BaseCurrencyPriceProjection> resolve(PriceSourceSnapshotQueryResult snapshot) {
        if (snapshot == null) {
            throw new PriceSourceProjectionException(Reason.INVALID_SNAPSHOT);
        }
        var byProductId = indexProducts(snapshot);
        var skuOwners = new HashMap<Long, ProductRows>();
        for (var sku : snapshot.skus()) {
            var owner = byProductId.get(sku.productId());
            if (owner == null || sku.id() <= 0 || skuOwners.putIfAbsent(sku.id(), owner) != null) {
                throw new PriceSourceProjectionException(Reason.INVALID_SNAPSHOT);
            }
            owner.skus.add(sku);
        }
        for (var row : snapshot.productPrices()) {
            var owner = byProductId.get(row.productId());
            if (owner == null) {
                throw new PriceSourceProjectionException(Reason.INVALID_SNAPSHOT);
            }
            owner.productPrices.add(row);
        }
        for (var row : snapshot.skuPrices()) {
            var owner = skuOwners.get(row.skuId());
            if (owner == null) {
                throw new PriceSourceProjectionException(Reason.INVALID_SNAPSHOT);
            }
            owner.skuPrices.add(row);
        }
        return snapshot.productIds().stream()
            .map(productId -> projectAndRequireMatch(byProductId.get(productId)))
            .toList();
    }

    private static Map<Long, ProductRows> indexProducts(PriceSourceSnapshotQueryResult snapshot) {
        var missing = new HashSet<Long>();
        for (long productId : snapshot.productIds()) {
            if (productId <= 0 || !missing.add(productId)) {
                throw new PriceSourceProjectionException(Reason.INVALID_SNAPSHOT);
            }
        }
        var byProductId = new HashMap<Long, ProductRows>();
        for (var product : snapshot.products()) {
            if (!missing.remove(product.id())) {
                throw new PriceSourceProjectionException(Reason.INVALID_SNAPSHOT);
            }
            byProductId.put(product.id(), new ProductRows(product));
        }
        if (!missing.isEmpty()) {
            throw new PriceSourceProjectionException(Reason.INVALID_SNAPSHOT);
        }
        return byProductId;
    }

    private static BaseCurrencyPriceProjection projectAndRequireMatch(ProductRows rows) {
        BaseCurrencyPriceProjection projection;
        try {
            projection = BaseCurrencyPriceProjection.project(rows.product, rows.skus, rows.productPrices, rows.skuPrices);
        } catch (IllegalStateException exception) {
            throw new PriceSourceProjectionException(Reason.INVALID_PRICE_BOOK);
        }
        try {
            LegacyPriceDrift.requireMatch(rows.product, rows.skus, projection);
        } catch (IllegalStateException exception) {
            throw new PriceSourceProjectionException(Reason.PRICE_DRIFT);
        }
        return projection;
    }

    private static final class ProductRows {
        private final Product product;
        private final List<Sku> skus = new ArrayList<>();
        private final List<ProductCurrencyPrice> productPrices = new ArrayList<>();
        private final List<SkuCurrencyPrice> skuPrices = new ArrayList<>();

        private ProductRows(Product product) {
            this.product = product;
        }
    }
}
