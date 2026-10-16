package com.commercefoundry.domainlayer.product.application.port.out.query;

import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;

/**
 * 고객 상품 목록 한 행. 목록에 필요한 컬럼만 읽는다 (상세 설명 HTML 같은 큰 컬럼을 끌어오지 않는다).
 *
 * @param listPriceAmount 정가. null 이면 할인 표시 없음 — 통화도 함께 null 이다
 */
public record ProductListQueryResult(
    long productId, String name, long salePriceAmount, CurrencyCode salePriceCurrency,
    Long listPriceAmount, CurrencyCode listPriceCurrency, SaleStatus saleStatus, boolean customOrder
) {
}
