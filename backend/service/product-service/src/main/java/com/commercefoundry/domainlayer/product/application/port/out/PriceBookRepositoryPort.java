package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import java.util.Collection;
import java.util.List;

/**
 * 통화별 가격 벌크 저장·조회. LegacyPriceWriteProcessor가 다섯 legacy writer의 가격행을 동기화한다.
 * 쓰기는 호출자의 DB 트랜잭션과 선행 부모 상품 CAS/@Version 보호가 필요하다.
 */
public interface PriceBookRepositoryPort {
    List<ProductCurrencyPrice> findProductPrices(Collection<Long> productIds);

    List<SkuCurrencyPrice> findSkuPrices(Collection<Long> skuIds);

    /** 신규 행만 persist한다. 기존 행 upsert나 추가 통화 삭제를 수행하지 않는다. */
    void insertAll(Collection<ProductCurrencyPrice> products, Collection<SkuCurrencyPrice> skus);

    /** 지정된 기존 행의 금액만 바꾸며 ID·소속·통화·createdAt과 요청 밖 행을 보존한다. */
    void updateAll(Collection<ProductCurrencyPrice> products, Collection<SkuCurrencyPrice> skus);
}
