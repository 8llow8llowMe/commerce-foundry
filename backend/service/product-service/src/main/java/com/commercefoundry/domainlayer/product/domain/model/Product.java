package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 상품. 가격 계산(SKU 판매가 · 할인율)은 {@link ProductPricing} 한 곳에서만 한다.
 *
 * @param categoryId 대표 카테고리 아이디 (상세 경로 표시용). 진열 카테고리 목록에도 반드시 들어 있다
 * @param descriptionHtml 상세 설명 HTML. 표시 전 sanitize 대상
 * @param salePrice 기본 판매가. SKU 추가금의 기준이자 목록 최저가
 * @param listPrice 정가 (할인 전). null 이면 할인 표시 없음
 * @param maxQuantityPerOrder 1회 주문 최대 수량. null 이면 제한 없음
 */
public record Product(
    long id, long categoryId, String name, String summary, String descriptionHtml,
    SaleStatus saleStatus, boolean visible, Money salePrice, Money listPrice, Integer maxQuantityPerOrder,
    boolean customOrder, String customOrderNotice, Integer customOrderLeadTimeDays
) {

    public Product {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(saleStatus, "saleStatus");
        Objects.requireNonNull(salePrice, "salePrice");
    }
}
