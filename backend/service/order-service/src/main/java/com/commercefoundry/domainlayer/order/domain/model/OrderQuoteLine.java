package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 주문서의 한 줄 — 주문 시점의 SKU 값(가격 · 상품명 · 옵션명 · 무게 · HS 코드)을 담는다. 주문 생성은 이 값을 주문 항목에 스냅숏한다
 * ({@code order-flow-guide.md} §2 · §2-2). 상품 · SKU 는 아이디로만 가리킨다 ({@code architecture-guide.md} §3).
 *
 * <p>생성할 때 막는 것 — 수량 1~{@value #MAX_QUANTITY}, 줄 금액 = 단가 × 수량, 맞춤 제작 입력은 맞춤 제작 상품에만. 업무 거절(상품 단위 수량 상한 등)은
 * Processor 가 오류 코드와 함께 먼저 판정하고, 이 검사는 조립 버그를 막는 마지막 선이다.
 *
 * @param productName 주문 시점 상품명
 * @param optionName 옵션 값 이름을 {@code " / "} 로 이은 표시명. 옵션이 없으면 빈 문자열
 * @param unitPrice 주문 시점 판매가 (기본 판매가 + 옵션 추가금)
 * @param lineAmount 줄 금액 = {@code unitPrice × quantity}
 * @param customOptions 맞춤 제작 입력. 없으면 빈 묶음
 * @param customOrder 맞춤 제작 상품인가 (주문 시점)
 * @param weightGrams SKU 단위 무게(그램). null 이면 미입력 — 국내 주문은 보지 않는다
 * @param hsCode 통관 HS 코드. null 이면 미입력 — 국내 주문은 보지 않는다
 */
public record OrderQuoteLine(
    long skuId, long productId, String productName, String optionName,
    Money unitPrice, int quantity, Money lineAmount, OrderCustomOptions customOptions, boolean customOrder,
    Integer weightGrams, String hsCode
) {

    /** 줄당 수량 상한 — 요청 검증(1~99)과 같다. 상품의 1회 주문 최대 수량은 상품 단위로 Processor 가 본다. */
    public static final int MAX_QUANTITY = 99;

    public OrderQuoteLine {
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(optionName, "optionName");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(lineAmount, "lineAmount");
        Objects.requireNonNull(customOptions, "customOptions");
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("줄 수량은 1 이상 %d 이하입니다: %d".formatted(MAX_QUANTITY, quantity));
        }
        if (!lineAmount.equals(unitPrice.times(quantity))) {
            throw new IllegalArgumentException("줄 금액이 단가 × 수량과 다릅니다: %s × %d ≠ %s".formatted(unitPrice, quantity, lineAmount));
        }
        if (!customOptions.isEmpty() && !customOrder) {
            throw new IllegalArgumentException("맞춤 제작 상품이 아닌 줄에 맞춤 제작 입력이 있습니다: skuId=" + skuId);
        }
    }

    /** 줄 금액을 단가 × 수량으로 계산해 만든다. */
    public static OrderQuoteLine of(
        long skuId, long productId, String productName, String optionName,
        Money unitPrice, int quantity, OrderCustomOptions customOptions, boolean customOrder,
        Integer weightGrams, String hsCode
    ) {
        Objects.requireNonNull(unitPrice, "unitPrice");
        return new OrderQuoteLine(
            skuId, productId, productName, optionName, unitPrice, quantity, unitPrice.times(quantity), customOptions, customOrder, weightGrams, hsCode);
    }
}
