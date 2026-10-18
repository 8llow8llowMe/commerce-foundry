package com.commercefoundry.domainlayer.product.application.model;

import com.commercefoundry.domainlayer.product.domain.model.ProductPricing;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuStocks;
import com.commercefoundry.shared.commerce.Money;
import java.util.Collection;
import java.util.LongSummaryStatistics;
import java.util.Objects;

/**
 * 상품 단위 표시 가격 · 판매 상태 (목록 항목 · 상세 상단). <b>계산은 {@link #of} 한 곳에서만 한다</b> — 목록은 SKU 집계 쿼리 결과로,
 * 상세는 메모리에 읽은 SKU · 재고로({@link #ofSkus}) 같은 규칙을 탄다. 둘이 갈라지면 목록과 상세가 같은 상품에 다른 가격 · 상태를 보인다.
 *
 * <p><b>{@code listPrice} 와 {@code discountRate} 는 함께 null 이거나 함께 값이 있다</b> (FE 계약 — 정가가 있으면 화면이 취소선을 그린다).
 * 정가가 없거나, 정가 ≤ 표시 판매가이거나(정가 0 포함), 할인율이 1% 미만이면 둘 다 null 이다.
 *
 * @param salePrice 표시 판매가 — {@link ProductPricing#displaySalePrice}
 * @param listPrice 정가. 할인이 없으면 null
 * @param discountRate 표시 판매가 기준 할인율 — {@link ProductPricing#discountRate}. 할인이 없으면 null
 * @param hasOptionSurcharge 판매중 SKU 끼리 추가금이 다른가 ("129,000원~" 표시)
 */
public record ProductSaleSummary(Money salePrice, Money listPrice, Integer discountRate, SaleAvailability saleStatus, boolean hasOptionSurcharge) {

    public ProductSaleSummary {
        Objects.requireNonNull(salePrice, "salePrice");
        Objects.requireNonNull(saleStatus, "saleStatus");
    }

    /**
     * @param hasOnSaleSku 판매중 SKU 가 하나라도 있는가. SKU 가 0건이면 false
     * @param hasPurchasableSku 판매중이면서 품절이 아닌 SKU 가 하나라도 있는가 — 재고가 무제한이거나 판매 가능 수량이 0 보다 크다. 재고 행이 없는 SKU 는 품절이다
     * @param minOnSaleAdditionalAmount 판매중 SKU 추가금의 최소값 (최소 화폐 단위). 판매중 SKU 가 없으면 null
     * @param maxOnSaleAdditionalAmount 판매중 SKU 추가금의 최대값. 판매중 SKU 가 없으면 null
     */
    public static ProductSaleSummary of(
        SaleStatus productSaleStatus, Money basePrice, Money listPrice,
        boolean hasOnSaleSku, boolean hasPurchasableSku, Long minOnSaleAdditionalAmount, Long maxOnSaleAdditionalAmount
    ) {
        Money salePrice = ProductPricing.displaySalePrice(basePrice, minOnSaleAdditionalAmount);
        boolean hasOptionSurcharge = minOnSaleAdditionalAmount != null && maxOnSaleAdditionalAmount != null
            && !minOnSaleAdditionalAmount.equals(maxOnSaleAdditionalAmount);
        SaleAvailability saleStatus = SaleAvailability.ofProduct(productSaleStatus, hasOnSaleSku, hasPurchasableSku);
        Integer discountRate = ProductPricing.discountRate(listPrice, salePrice);
        // 할인으로 보이지 않는 정가(표시 판매가 이하 · 1% 미만 할인)는 내리지 않는다 — 내리면 취소선만 있고 할인율은 없는 화면이 된다
        Money displayedListPrice = discountRate == null ? null : listPrice;
        return new ProductSaleSummary(salePrice, displayedListPrice, discountRate, saleStatus, hasOptionSurcharge);
    }

    /**
     * 상세 — 메모리에 읽은 SKU · 재고에서 판매중(ON_SALE) SKU 의 추가금 최소 · 최대와 구매 가능 SKU 유무를 구해 {@link #of} 로 넘긴다.
     * 구매 가능 = 판매중이면서 품절이 아니다 — 목록의 SKU 집계 쿼리와 같은 규칙이다. 품절 SKU 의 추가금도 최소 · 최대에 넣는다 (목록과 같다).
     *
     * @param stocks SKU 들의 재고. 재고 행이 없는 SKU 는 품절이다
     */
    public static ProductSaleSummary ofSkus(SaleStatus productSaleStatus, Money basePrice, Money listPrice, Collection<Sku> skus, SkuStocks stocks) {
        LongSummaryStatistics onSale = skus.stream()
            .filter(Sku::isOnSale)
            .mapToLong(sku -> sku.additionalPrice().amount())
            .summaryStatistics();
        boolean hasOnSaleSku = onSale.getCount() > 0;
        boolean hasPurchasableSku = skus.stream().anyMatch(sku -> sku.isOnSale() && !stocks.snapshotOf(sku.id()).isSoldOut());
        return of(
            productSaleStatus, basePrice, listPrice,
            hasOnSaleSku, hasPurchasableSku, hasOnSaleSku ? onSale.getMin() : null, hasOnSaleSku ? onSale.getMax() : null
        );
    }
}
