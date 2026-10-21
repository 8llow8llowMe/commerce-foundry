package com.commercefoundry.domainlayer.product.application.port.out.query;

import com.commercefoundry.domainlayer.product.application.mapper.MoneyColumns;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/**
 * 어드민 상품 목록 한 행. 목록에 필요한 컬럼만 읽는다 (상세 설명 HTML 같은 큰 컬럼을 끌어오지 않는다).
 * 금액 · 통화 짝 검증은 {@link MoneyColumns} 한 곳에서 한다.
 *
 * @param listPriceAmount 정가. null 이면 정가 없음 — 통화도 함께 null 이다
 * @param updatedAt 상품 행의 수정 시각. SKU · 재고 · 이미지만 바뀐 것은 반영하지 않는다
 */
public record ProductAdminListQueryResult(
    long productId, String name, long salePriceAmount, CurrencyCode salePriceCurrency,
    Long listPriceAmount, CurrencyCode listPriceCurrency, SaleStatus saleStatus, boolean visible, Instant updatedAt
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
