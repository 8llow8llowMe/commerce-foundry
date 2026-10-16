package com.commercefoundry.domainlayer.product.application.port.out.query;

import com.commercefoundry.domainlayer.product.application.mapper.MoneyColumns;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;

/**
 * 고객 상품 목록 한 행. 목록에 필요한 컬럼만 읽는다 (상세 설명 HTML 같은 큰 컬럼을 끌어오지 않는다).
 *
 * <p>컬럼은 저장된 그대로 받고(QueryDSL 프로젝션), 금액은 {@link #salePrice()} · {@link #listPrice()} 로 꺼낸다 —
 * 금액 · 통화 짝 검증은 {@link MoneyColumns} 한 곳에서 한다.
 *
 * @param listPriceAmount 정가. null 이면 할인 표시 없음 — 통화도 함께 null 이다
 */
public record ProductListQueryResult(
    long productId, String name, long salePriceAmount, CurrencyCode salePriceCurrency,
    Long listPriceAmount, CurrencyCode listPriceCurrency, SaleStatus saleStatus, boolean customOrder
) {

    /** 기본 판매가. */
    public Money salePrice() {
        return MoneyColumns.required(salePriceAmount, salePriceCurrency);
    }

    /** 정가. 없으면 null 이다. */
    public Money listPrice() {
        return MoneyColumns.nullable(listPriceAmount, listPriceCurrency);
    }
}
