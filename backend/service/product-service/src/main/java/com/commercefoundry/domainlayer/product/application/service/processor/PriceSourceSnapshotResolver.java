package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.port.out.query.PriceSourceSnapshotQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.BaseCurrencyPriceProjection;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceDrift;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** B1의 읽기 스냅숏을 상품별로 검증하고 투영한다. 생산 조회 경로에는 아직 연결하지 않는다. */
public final class PriceSourceSnapshotResolver {
    private PriceSourceSnapshotResolver() {
    }

    public static List<BaseCurrencyPriceProjection> resolve(PriceSourceSnapshotQueryResult snapshot) {
        if (snapshot == null) {
            throw new ResolutionException(Reason.INVALID_SNAPSHOT);
        }
        try {
            return resolveValid(snapshot);
        } catch (ResolutionException exception) {
            throw exception;
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new ResolutionException(Reason.INVALID_SNAPSHOT);
        }
    }

    private static List<BaseCurrencyPriceProjection> resolveValid(PriceSourceSnapshotQueryResult snapshot) {
        var requested = new HashSet<>(snapshot.productIds());
        if (requested.size() != snapshot.productIds().size() || requested.contains(null)) {
            throw new ResolutionException(Reason.INVALID_SNAPSHOT);
        }
        Map<Long, Product> products = new HashMap<>();
        for (var product : snapshot.products()) {
            if (products.putIfAbsent(product.id(), product) != null) {
                throw new ResolutionException(Reason.INVALID_SNAPSHOT);
            }
        }
        if (!products.keySet().equals(requested)) {
            throw new ResolutionException(Reason.PRODUCT_SET_MISMATCH);
        }
        Map<Long, List<Sku>> skus = new HashMap<>();
        Set<Long> skuIds = new HashSet<>();
        Map<Long, Long> skuOwners = new HashMap<>();
        for (var sku : snapshot.skus()) {
            if (!products.containsKey(sku.productId())) {
                throw new ResolutionException(Reason.FOREIGN_ROW);
            }
            if (!skuIds.add(sku.id())) {
                throw new ResolutionException(Reason.INVALID_SNAPSHOT);
            }
            skus.computeIfAbsent(sku.productId(), ignored -> new ArrayList<>()).add(sku);
            skuOwners.put(sku.id(), sku.productId());
        }
        Map<Long, List<ProductCurrencyPrice>> productPrices = new HashMap<>();
        Set<Long> productPriceIds = new HashSet<>();
        for (var row : snapshot.productPrices()) {
            if (!products.containsKey(row.productId())) {
                throw new ResolutionException(Reason.FOREIGN_ROW);
            }
            if (!productPriceIds.add(row.id())) {
                throw new ResolutionException(Reason.INVALID_SNAPSHOT);
            }
            productPrices.computeIfAbsent(row.productId(), ignored -> new ArrayList<>()).add(row);
        }
        Map<Long, List<SkuCurrencyPrice>> skuPrices = new HashMap<>();
        Set<Long> skuPriceIds = new HashSet<>();
        for (var row : snapshot.skuPrices()) {
            var owner = skuOwners.get(row.skuId());
            if (owner == null) {
                throw new ResolutionException(Reason.FOREIGN_ROW);
            }
            if (!skuPriceIds.add(row.id())) {
                throw new ResolutionException(Reason.INVALID_SNAPSHOT);
            }
            skuPrices.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(row);
        }
        var result = new ArrayList<BaseCurrencyPriceProjection>(snapshot.productIds().size());
        for (var id : snapshot.productIds()) {
            var legacy = products.get(id);
            var productSkus = skus.getOrDefault(id, List.of());
            try {
                LegacyPriceSnapshot.of(legacy, productSkus);
            } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
                throw new ResolutionException(Reason.INVALID_LEGACY);
            }
            BaseCurrencyPriceProjection projection;
            try {
                projection = BaseCurrencyPriceProjection.project(legacy, productSkus,
                    productPrices.getOrDefault(id, List.of()), skuPrices.getOrDefault(id, List.of()));
            } catch (IllegalStateException exception) {
                throw new ResolutionException(Reason.INVALID_PRICE_BOOK);
            }
            try {
                LegacyPriceDrift.requireMatch(legacy, productSkus, projection);
            } catch (IllegalStateException exception) {
                throw new ResolutionException(Reason.LEGACY_DRIFT);
            }
            result.add(projection);
        }
        return List.copyOf(result);
    }

    public enum Reason { INVALID_SNAPSHOT, PRODUCT_SET_MISMATCH, FOREIGN_ROW, INVALID_LEGACY, INVALID_PRICE_BOOK, LEGACY_DRIFT }

    public static final class ResolutionException extends IllegalStateException {
        private final Reason reason;

        private ResolutionException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }
}
