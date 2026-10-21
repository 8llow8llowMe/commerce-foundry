package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Objects;

/**
 * 주문서 — 서버가 계산한 주문 금액이다. 주문서 미리보기와 주문 생성이 같은 계산({@code OrderQuoteProcessor#quote})으로 이 값을 만든다 ({@code order-flow-guide.md} §2-3).
 * 프론트가 보낸 금액 · 가격은 쓰지 않는다.
 *
 * <p>생성할 때 막는 것 — 줄이 하나 이상, 모든 금액이 주문 통화, 상품 합계 = Σ 줄 금액, 총액 = 상품 합계 + 배송비, 견적 범위가 배송 국가와 맞음.
 *
 * @param currency 주문 통화 — SKU 판매가의 통화
 * @param lines 요청 순서
 * @param itemsAmount 상품 합계 = Σ 줄 금액 (배송비 제외. 할인은 아직 없다). 배송비 견적의 기준 금액이다
 * @param shippingFeeQuote 배송비 견적
 * @param totalAmount 총액 = 상품 합계 + 배송비
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 */
public record OrderQuote(
    CurrencyCode currency, List<OrderQuoteLine> lines, Money itemsAmount, ShippingFeeQuote shippingFeeQuote, Money totalAmount, String countryCode
) {

    public OrderQuote {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(itemsAmount, "itemsAmount");
        Objects.requireNonNull(shippingFeeQuote, "shippingFeeQuote");
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(countryCode, "countryCode");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문서에는 줄이 하나 이상 있어야 합니다");
        }
        if (lines.stream().anyMatch(line -> line.unitPrice().currency() != currency) || shippingFeeQuote.shippingFee().currency() != currency) {
            throw new IllegalArgumentException("주문서 금액의 통화가 주문 통화(%s)와 다릅니다".formatted(currency));
        }
        Money linesTotal = lines.stream().map(OrderQuoteLine::lineAmount).reduce(Money.zero(currency), Money::plus);
        if (!itemsAmount.equals(linesTotal)) {
            throw new IllegalArgumentException("상품 합계가 줄 금액의 합과 다릅니다: %s ≠ %s".formatted(itemsAmount, linesTotal));
        }
        if (!totalAmount.equals(itemsAmount.plus(shippingFeeQuote.shippingFee()))) {
            throw new IllegalArgumentException("총액이 상품 합계 + 배송비와 다릅니다: %s".formatted(totalAmount));
        }
        if (shippingFeeQuote.scope() != ShippingScope.ofCountry(countryCode)) {
            throw new IllegalArgumentException("배송비 견적 범위가 배송 국가와 맞지 않습니다: %s, %s".formatted(shippingFeeQuote.scope(), countryCode));
        }
    }
}
