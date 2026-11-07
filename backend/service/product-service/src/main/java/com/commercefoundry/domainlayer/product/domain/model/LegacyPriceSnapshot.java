package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 부모 상품 보호 아래 읽은 legacy 가격과 전체 SKU의 불변 복사본. 재고는 포함하지 않는다. */
public record LegacyPriceSnapshot(long productId, Money salePrice, Money listPrice, List<Sku> skus) {
    public LegacyPriceSnapshot {
        if (productId <= 0 || salePrice == null || skus == null || skus.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("legacy 가격 스냅숏 입력이 올바르지 않습니다");
        }
        skus = List.copyOf(skus);
        var ids = new HashSet<Long>();
        for (var sku : skus) {
            if (sku.id() <= 0 || sku.productId() != productId || !ids.add(sku.id())
                || sku.additionalPrice().currency() != salePrice.currency()) {
                throw new IllegalStateException("legacy SKU 소속·식별자·기준통화가 올바르지 않습니다");
            }
        }
    }

    public static LegacyPriceSnapshot of(Product product, List<Sku> skus) {
        return new LegacyPriceSnapshot(product.id(), product.salePrice(), product.listPrice(), skus);
    }

    public Map<Long, SaleStatus> skuStatuses() {
        return skus.stream().collect(Collectors.toUnmodifiableMap(Sku::id, Sku::saleStatus));
    }

    /** 기존 SKU 제거와 기준통화 변경은 요청 가격규칙이 아닌 내부 호출 계약 오류다. */
    public void requireSuccessor(LegacyPriceSnapshot after) {
        if (after == null || productId != after.productId || salePrice.currency() != after.salePrice.currency()
            || !after.skuStatuses().keySet().containsAll(skuStatuses().keySet())) {
            throw new IllegalStateException("legacy 가격 스냅숏 전이 계약이 올바르지 않습니다");
        }
    }
}
