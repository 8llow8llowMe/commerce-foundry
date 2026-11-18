package com.commercefoundry.domainlayer.product.domain.model;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** 투영과 분리한 legacy 대조. 차이가 있어도 어느 입력도 변경하거나 자동 복구하지 않는다. */
public final class LegacyPriceDrift {
    private LegacyPriceDrift() {
    }

    /** SKU 순서는 대조 기준이 아니며 소속·식별자·기준통화와 실제 금액을 O(n)으로 대조한다. */
    public static void requireMatch(Product legacy, List<Sku> skus, BaseCurrencyPriceProjection projection) {
        try {
            var snapshot = LegacyPriceSnapshot.of(legacy, skus);
            var projected = projection.product();
            if (legacy.id() != projected.id() || !snapshot.salePrice().equals(projected.salePrice())
                || !Objects.equals(snapshot.listPrice(), projected.listPrice()) || skus.size() != projection.skus().size()) {
                throw new IllegalStateException();
            }
            var byId = new HashMap<Long, Sku>();
            projection.skus().forEach(sku -> byId.put(sku.id(), sku));
            for (var sku : snapshot.skus()) {
                var target = byId.get(sku.id());
                if (target == null || !sku.additionalPrice().equals(target.additionalPrice())) {
                    throw new IllegalStateException();
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new IllegalStateException("legacy 가격과 기준통화 가격행이 일치하지 않습니다");
        }
    }
}
