package com.commercefoundry.domainlayer.cart.support;

import com.commercefoundry.domainlayer.cart.application.port.out.SkuQueryPort;
import com.commercefoundry.domainlayer.cart.application.port.out.query.SkuQueryResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * product-service 를 흉내 낸다. 테스트가 SKU 의 가격 · 상태를 호출 사이에 바꿔 "조회 시점의 현재 값" 을 본다. 호출마다 받은 아이디를 남긴다.
 */
public class FakeSkuQueryPort implements SkuQueryPort {

    private final Map<Long, SkuQueryResult> skus = new LinkedHashMap<>();
    private final List<List<Long>> calls = new ArrayList<>();

    public FakeSkuQueryPort put(SkuQueryResult sku) {
        skus.put(sku.skuId(), sku);
        return this;
    }

    public FakeSkuQueryPort remove(long skuId) {
        skus.remove(skuId);
        return this;
    }

    @Override
    public List<SkuQueryResult> findSkus(Collection<Long> skuIds) {
        List<Long> distinct = skuIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        calls.add(distinct);
        return distinct.stream().map(skus::get).filter(Objects::nonNull).toList();
    }

    public List<List<Long>> calls() {
        return calls;
    }
}
