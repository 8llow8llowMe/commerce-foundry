package com.commercefoundry.domainlayer.product.domain.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 검증된 기준통화 쓰기만 담는 순수 계획. 추가 통화는 검증에만 사용한다.
 * 부모 상품 보호를 확보한 동일 트랜잭션에서 준비·소비하며 캐시하거나 다음 트랜잭션으로 전달하지 않는다.
 */
public final class LegacyPriceWritePlan {
    private final List<ProductCurrencyPrice> productInserts;
    private final List<SkuCurrencyPrice> skuInserts;
    private final List<ProductCurrencyPrice> productUpdates;
    private final List<SkuCurrencyPrice> skuUpdates;

    private LegacyPriceWritePlan(List<ProductCurrencyPrice> productInserts, List<SkuCurrencyPrice> skuInserts,
                                 List<ProductCurrencyPrice> productUpdates, List<SkuCurrencyPrice> skuUpdates) {
        this.productInserts = List.copyOf(productInserts);
        this.skuInserts = List.copyOf(skuInserts);
        this.productUpdates = List.copyOf(productUpdates);
        this.skuUpdates = List.copyOf(skuUpdates);
    }

    public List<ProductCurrencyPrice> productInserts() { return productInserts; }
    public List<SkuCurrencyPrice> skuInserts() { return skuInserts; }
    public List<ProductCurrencyPrice> productUpdates() { return productUpdates; }
    public List<SkuCurrencyPrice> skuUpdates() { return skuUpdates; }

    public static LegacyPriceWritePlan prepare(LegacyPriceSnapshot before, LegacyPriceSnapshot after,
                                               List<ProductCurrencyPrice> products, List<SkuCurrencyPrice> skus, LongSupplier ids) {
        before.requireSuccessor(after);
        var base = before.salePrice().currency();
        var beforeSkus = new HashMap<Long, Sku>();
        before.skus().forEach(sku -> beforeSkus.put(sku.id(), sku));
        var productIds = new HashSet<Long>();
        var skuIds = new HashSet<Long>();
        var currencies = new HashSet<com.commercefoundry.shared.commerce.CurrencyCode>();
        var logicalSkuKeys = new HashSet<String>();
        ProductCurrencyPrice baseProduct = null;
        var baseSkus = new HashMap<Long, SkuCurrencyPrice>();
        // 구조·drift를 먼저 검사한다. 신규 SKU ID에 이미 있는 행은 추가 통화도 거절한다.
        for (var row : products) {
            if (row.productId() != before.productId() || !productIds.add(row.id()) || !currencies.add(row.salePrice().currency())) {
                throw invalidBefore();
            }
            if (row.salePrice().currency() == base) {
                if (!row.salePrice().equals(before.salePrice()) || !Objects.equals(row.listPrice(), before.listPrice())) {
                    throw invalidBefore();
                }
                baseProduct = row;
            }
        }
        for (var row : skus) {
            if (!beforeSkus.containsKey(row.skuId()) || !skuIds.add(row.id())
                || !logicalSkuKeys.add(row.skuId() + ":" + row.additionalPrice().currency())
                || !currencies.contains(row.additionalPrice().currency()) && row.additionalPrice().currency() != base) {
                throw invalidBefore();
            }
            if (row.additionalPrice().currency() == base) {
                if (!row.additionalPrice().equals(beforeSkus.get(row.skuId()).additionalPrice())) {
                    throw invalidBefore();
                }
                baseSkus.put(row.skuId(), row);
            }
        }
        var completedProducts = new ArrayList<>(products);
        var completedSkus = new ArrayList<>(skus);
        boolean missingProduct = baseProduct == null;
        if (missingProduct) {
            long id = nextId(ids, productIds);
            try {
                baseProduct = new ProductCurrencyPrice(id, before.productId(), before.salePrice(), before.listPrice());
            } catch (IllegalArgumentException exception) {
                throw invalidBefore();
            }
            completedProducts.add(baseProduct);
        }
        var missingSkus = new HashSet<Long>();
        for (var sku : before.skus()) {
            if (!baseSkus.containsKey(sku.id())) {
                var row = new SkuCurrencyPrice(nextId(ids, skuIds), sku.id(), sku.additionalPrice());
                baseSkus.put(sku.id(), row);
                completedSkus.add(row);
                missingSkus.add(sku.id());
            }
        }
        try {
            new PriceBook(before.productId(), base, before.skuStatuses(), completedProducts, completedSkus);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw invalidBefore();
        }
        // ID 발급 실패·충돌은 after 요청규칙 오류로 번역하지 않는다.
        for (var sku : after.skus()) {
            if (!baseSkus.containsKey(sku.id())) {
                baseSkus.put(sku.id(), new SkuCurrencyPrice(nextId(ids, skuIds), sku.id(), sku.additionalPrice()));
                missingSkus.add(sku.id());
            }
        }
        return finish(after, completedProducts, completedSkus, baseProduct, baseSkus, missingProduct, missingSkus);
    }

    public static LegacyPriceWritePlan prepareNew(LegacyPriceSnapshot after, List<ProductCurrencyPrice> products,
                                                  List<SkuCurrencyPrice> skus, LongSupplier ids) {
        if (!products.isEmpty() || !skus.isEmpty()) {
            throw new IllegalStateException("신규 상품 또는 SKU의 가격행이 이미 존재합니다");
        }
        long productId = nextId(ids, new HashSet<>());
        var skuIds = new HashSet<Long>();
        var baseSkus = new HashMap<Long, SkuCurrencyPrice>();
        for (var sku : after.skus()) {
            baseSkus.put(sku.id(), new SkuCurrencyPrice(nextId(ids, skuIds), sku.id(), sku.additionalPrice()));
        }
        ProductCurrencyPrice product;
        try {
            product = new ProductCurrencyPrice(productId, after.productId(), after.salePrice(), after.listPrice());
        } catch (IllegalArgumentException exception) {
            throw new RequestRuleViolationException();
        }
        return finish(after, List.of(product), List.of(), product, baseSkus, true, baseSkus.keySet());
    }

    private static LegacyPriceWritePlan finish(LegacyPriceSnapshot after, List<ProductCurrencyPrice> products, List<SkuCurrencyPrice> skus,
                                               ProductCurrencyPrice baseProduct, Map<Long, SkuCurrencyPrice> baseSkus,
                                               boolean missingProduct, Set<Long> missingSkus) {
        var productInserts = new ArrayList<ProductCurrencyPrice>();
        var productUpdates = new ArrayList<ProductCurrencyPrice>();
        var skuInserts = new ArrayList<SkuCurrencyPrice>();
        var skuUpdates = new ArrayList<SkuCurrencyPrice>();
        var finalProducts = new ArrayList<ProductCurrencyPrice>();
        var finalSkus = new ArrayList<SkuCurrencyPrice>();
        // before가 유효함을 확인한 뒤에만 요청으로 인한 가격규칙 위반을 구분한다.
        try {
            var finalProduct = new ProductCurrencyPrice(baseProduct.id(), after.productId(), after.salePrice(), after.listPrice());
            if (missingProduct) {
                productInserts.add(finalProduct);
            } else if (!finalProduct.equals(baseProduct)) {
                productUpdates.add(finalProduct);
            }
            products.forEach(row -> finalProducts.add(row.salePrice().currency() == after.salePrice().currency() ? finalProduct : row));
            skus.stream().filter(row -> row.additionalPrice().currency() != after.salePrice().currency()).forEach(finalSkus::add);
            for (var sku : after.skus()) {
                var old = baseSkus.get(sku.id());
                var row = new SkuCurrencyPrice(old.id(), sku.id(), sku.additionalPrice());
                finalSkus.add(row);
                if (missingSkus.contains(sku.id())) {
                    skuInserts.add(row);
                } else if (!row.equals(old)) {
                    skuUpdates.add(row);
                }
            }
            new PriceBook(after.productId(), after.salePrice().currency(), after.skuStatuses(), finalProducts, finalSkus);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new RequestRuleViolationException();
        }
        return new LegacyPriceWritePlan(productInserts, skuInserts, productUpdates, skuUpdates);
    }

    private static long nextId(LongSupplier ids, Set<Long> used) {
        long id;
        try {
            id = ids.getAsLong();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("가격행 식별자 발급에 실패했습니다");
        }
        if (id <= 0 || !used.add(id)) {
            throw new IllegalStateException("가격행 식별자가 유효하지 않거나 중복됩니다");
        }
        return id;
    }

    private static IllegalStateException invalidBefore() {
        return new IllegalStateException("기존 가격행 또는 legacy 가격 불변식이 올바르지 않습니다");
    }

    /** B의 writer가 PRODUCT_016 또는 SKU_ADMIN_004로 변환할 요청 가격규칙 위반. 금액 원문을 싣지 않는다. */
    public static final class RequestRuleViolationException extends RuntimeException {
        private RequestRuleViolationException() {
            super("변경 후 통화별 가격규칙을 만족하지 않습니다");
        }
    }
}
