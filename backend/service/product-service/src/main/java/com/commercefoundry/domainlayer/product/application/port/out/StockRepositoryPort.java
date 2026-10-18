package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.Stock;
import java.util.Collection;
import java.util.List;

/**
 * SKU 재고. 컬렉션 인자 조회는 빈 컬렉션이면 쿼리 없이 빈 목록이다.
 */
public interface StockRepositoryPort {

    /**
     * SKU 아이디 in 절 한 번으로 읽는다. 재고 행이 없는 SKU 는 결과에 없다 — 품절로 본다 ({@code SkuStocks#snapshotOf}).
     */
    List<Stock> findAllBySkuIds(Collection<Long> skuIds);
}
