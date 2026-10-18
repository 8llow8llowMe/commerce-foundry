package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SKU 아이디 → 재고. 상세 · 서비스 간 SKU 조회가 SKU 아이디 in 절 한 번으로 읽은 재고 행을 들고 다닌다.
 *
 * <p>"재고 행이 없는 SKU 는 품절" 규칙은 {@link #snapshotOf} 한 곳에서만 지킨다 — 호출한 쪽이 저마다 기본값을 고르지 않게.
 * 목록은 같은 규칙을 SKU 집계 쿼리({@code SkuCustomRepositoryImpl#aggregateByProductIds})의 stock left join 으로 탄다.
 */
public final class SkuStocks {

    private final Map<Long, StockSnapshot> snapshotsBySkuId;

    private SkuStocks(Map<Long, StockSnapshot> snapshotsBySkuId) {
        this.snapshotsBySkuId = snapshotsBySkuId;
    }

    /** SKU 아이디가 겹치지 않는다 — {@code stock} 의 PK 가 {@code sku_id} 다. */
    public static SkuStocks of(Collection<Stock> stocks) {
        return new SkuStocks(stocks.stream().collect(Collectors.toUnmodifiableMap(Stock::skuId, Stock::toSnapshot)));
    }

    /** 재고 행이 없으면 {@link StockSnapshot#empty()} (품절)다. */
    public StockSnapshot snapshotOf(long skuId) {
        return snapshotsBySkuId.getOrDefault(skuId, StockSnapshot.empty());
    }
}
