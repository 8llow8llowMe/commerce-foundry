package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 고객 화면의 상품 카드 한 장 — 상품 목록 항목과 같은 값이다. 가격 · 할인 · 판매 상태는 상품 목록과 같은 계산({@code ProductSaleSummary#of})으로
 * {@code ProductListProcessor} 가 만든다. 같은 서비스의 다른 컨텍스트(전시)는 이 domain model 만 받는다 — 상품 Info 를 쓰지 않는다
 * ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 *
 * @param thumbnail 대표 이미지. 없으면 null
 * @param listPrice 정가. 할인이 없으면 null — {@code discountRate} 와 함께 null 이다
 * @param discountRate 할인율(%). 할인이 없으면 null
 * @param hasOptionSurcharge 판매중 SKU 끼리 추가금이 다른가
 * @param customOrder 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 true
 */
public record ProductSummary(
    long productId, String name, ProductImage thumbnail,
    Money salePrice, Money listPrice, Integer discountRate, boolean hasOptionSurcharge,
    SaleAvailability saleStatus, boolean customOrder
) {

    public ProductSummary {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(salePrice, "salePrice");
        Objects.requireNonNull(saleStatus, "saleStatus");
    }
}
