package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 주문 수량 상한 기본값 ({@code order.quantity-limit.*}, #426 · {@code order-flow-guide.md} §2-3). 잘못된 값은 기동을 막는다.
 *
 * @param defaultPerProduct 상품의 {@code maxQuantityPerOrder} 가 비어 있을 때 쓰는 상품당 상한(1–99). 비어 있다고 무제한으로 두면 회원 한 명이 시도 하나로
 *                          재고가 적은 상품(맞춤 · 한정)을 전부 선점할 수 있다. 상품에 값을 두면 그 값이 이긴다(더 크게도 둘 수 있다)
 */
@ConfigurationProperties("order.quantity-limit")
public record OrderQuantityLimitProperties(@DefaultValue("10") int defaultPerProduct) {

    public OrderQuantityLimitProperties {
        if (defaultPerProduct < 1 || defaultPerProduct > 99) {
            throw new IllegalArgumentException("order.quantity-limit.default-per-product 는 1–99 여야 합니다: " + defaultPerProduct);
        }
    }

    /** 상품의 상한 — 상품 값이 있으면 그것, 없으면 기본값. */
    public int maxPerProduct(Integer productMaxQuantityPerOrder) {
        return productMaxQuantityPerOrder == null ? defaultPerProduct : productMaxQuantityPerOrder;
    }
}
