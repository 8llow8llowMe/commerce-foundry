package com.commercefoundry.domainlayer.product.application.port.out.query;

/**
 * 상품별 SKU 집계 — 목록이 SKU 를 상품마다 따로 읽지 않게 한 번의 group by 로 낸다. 판매중(ON_SALE) SKU 만 센다.
 * 판매 종료 SKU 의 추가금은 살 수 없는 가격이라 목록 가격에 섞지 않는다.
 *
 * <p>목록(B PR)은 이렇게 쓴다.
 * <ul>
 *   <li>목록 판매가 = 상품 기본 판매가 + {@code minOnSaleAdditionalAmount}. 판매중 SKU 가 없으면(null) 기본 판매가</li>
 *   <li>"옵션에 따라 가격 상이" 표시({@code hasOptionSurcharge}) = {@code minOnSaleAdditionalAmount ≠ maxOnSaleAdditionalAmount}</li>
 *   <li>판매 가능 상태 = {@code SaleAvailability.ofProduct(상품 판매 상태, hasOnSaleSku, 구매 가능 SKU 유무)}</li>
 * </ul>
 *
 * <p>가격 정렬은 기본 판매가({@code product.sale_price_amount}) 기준이다. 상품 등록(#9)이 "기본 판매가 = 판매중 SKU 최저가
 * (판매중 SKU 의 최소 추가금 0)" 불변식을 지키면 목록에 보이는 판매가와 정렬 순서가 일치한다.
 *
 * <p>SKU 가 하나도 없는 상품은 집계 결과에 행이 없다 — 호출한 쪽은 행이 없으면 {@code hasOnSaleSku = false} 로 본다.
 *
 * @param hasOnSaleSku 판매중 SKU 가 하나라도 있는가
 * @param minOnSaleAdditionalAmount 판매중 SKU 추가금의 최소값 (최소 화폐 단위, 통화는 상품 기본 판매가와 같다). 판매중 SKU 가 없으면 null
 * @param maxOnSaleAdditionalAmount 판매중 SKU 추가금의 최대값. 판매중 SKU 가 없으면 null
 */
public record SkuAggregateQueryResult(long productId, boolean hasOnSaleSku, Long minOnSaleAdditionalAmount, Long maxOnSaleAdditionalAmount) {
}
