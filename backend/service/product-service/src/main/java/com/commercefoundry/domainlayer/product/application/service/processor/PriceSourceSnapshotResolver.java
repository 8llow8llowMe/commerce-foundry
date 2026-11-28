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

/**
 * B1의 읽기 스냅숏을 상품별로 검증하고 투영한다. 생산 조회 경로 연결은 PRICE_BOOK 모드의 고객 상품 상세(B4, {@link #resolve})와
 * 서비스 간 SKU 조회(B4b, {@link #resolveEach})다.
 *
 * <p>실패는 두 층이다. 묶음 구조 오류(요청 아이디 중복 · 상품 집합 불일치 · 다른 상품 소속 행 · 행 아이디 중복)는 어느 상품의 문제인지 가를 수 없어
 * 두 메서드 모두 묶음 전체의 {@link ResolutionException} 이다. 상품별 오류(legacy 입력 · 가격행 불변식 · legacy drift)는 {@link #resolve} 가 요청 순서상
 * 처음 실패한 상품의 사유로 묶음 전체를 거절하고, {@link #resolveEach} 는 그 상품의 결과로만 돌려준다.
 */
public final class PriceSourceSnapshotResolver {
    private PriceSourceSnapshotResolver() {
    }

    /** 묶음 전체를 투영한다. 한 상품이라도 실패하면 요청 순서상 처음 실패한 상품의 사유로 묶음 전체가 예외다 — 부분 결과를 돌려주지 않는다. */
    public static List<BaseCurrencyPriceProjection> resolve(PriceSourceSnapshotQueryResult snapshot) {
        var outcomes = resolveEach(snapshot);
        var result = new ArrayList<BaseCurrencyPriceProjection>(outcomes.size());
        for (var outcome : outcomes) {
            if (!outcome.isResolved()) {
                throw new ResolutionException(outcome.failure());
            }
            result.add(outcome.projection());
        }
        return List.copyOf(result);
    }

    /**
     * 상품마다 투영하거나 실패 사유를 낸다. 결과는 스냅숏의 요청 순서이고 상품마다 정확히 하나다.
     *
     * @throws ResolutionException 스냅숏이 null 이거나 묶음 구조 오류일 때 — 상품별 결과를 만들 수 없다
     */
    public static List<Outcome> resolveEach(PriceSourceSnapshotQueryResult snapshot) {
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

    private static List<Outcome> resolveValid(PriceSourceSnapshotQueryResult snapshot) {
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
        var result = new ArrayList<Outcome>(snapshot.productIds().size());
        for (var id : snapshot.productIds()) {
            result.add(projectOne(id, products.get(id), skus.getOrDefault(id, List.of()),
                productPrices.getOrDefault(id, List.of()), skuPrices.getOrDefault(id, List.of())));
        }
        return List.copyOf(result);
    }

    /** 상품 하나의 legacy 검증 → 투영 → drift 대조. 단계마다 사유가 다르고, 실패해도 다른 상품의 결과에 영향을 주지 않는다. */
    private static Outcome projectOne(long id, Product legacy, List<Sku> productSkus,
                                      List<ProductCurrencyPrice> productPrices, List<SkuCurrencyPrice> skuPrices) {
        try {
            LegacyPriceSnapshot.of(legacy, productSkus);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            return Outcome.failed(id, Reason.INVALID_LEGACY);
        }
        BaseCurrencyPriceProjection projection;
        try {
            projection = BaseCurrencyPriceProjection.project(legacy, productSkus, productPrices, skuPrices);
        } catch (IllegalStateException exception) {
            return Outcome.failed(id, Reason.INVALID_PRICE_BOOK);
        }
        try {
            LegacyPriceDrift.requireMatch(legacy, productSkus, projection);
        } catch (IllegalStateException exception) {
            return Outcome.failed(id, Reason.LEGACY_DRIFT);
        }
        return Outcome.resolved(id, projection);
    }

    public enum Reason { INVALID_SNAPSHOT, PRODUCT_SET_MISMATCH, FOREIGN_ROW, INVALID_LEGACY, INVALID_PRICE_BOOK, LEGACY_DRIFT }

    /**
     * 상품 하나의 해석 결과 — 투영과 실패 사유 중 정확히 하나만 있다. {@code toString} 은 아이디 · 금액 없이 사유만 낸다.
     *
     * @param failure 상품별 사유({@code INVALID_LEGACY} · {@code INVALID_PRICE_BOOK} · {@code LEGACY_DRIFT}). 투영에 성공하면 null
     */
    public record Outcome(long productId, BaseCurrencyPriceProjection projection, Reason failure) {
        public Outcome {
            if ((projection == null) == (failure == null)) {
                throw new IllegalArgumentException("투영과 실패 사유 중 하나만 있어야 합니다");
            }
        }

        static Outcome resolved(long productId, BaseCurrencyPriceProjection projection) {
            return new Outcome(productId, projection, null);
        }

        static Outcome failed(long productId, Reason failure) {
            return new Outcome(productId, null, failure);
        }

        public boolean isResolved() {
            return failure == null;
        }

        @Override
        public String toString() {
            return "Outcome[failure=" + failure + "]";
        }
    }

    public static final class ResolutionException extends IllegalStateException {
        private final Reason reason;

        private ResolutionException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }
}
