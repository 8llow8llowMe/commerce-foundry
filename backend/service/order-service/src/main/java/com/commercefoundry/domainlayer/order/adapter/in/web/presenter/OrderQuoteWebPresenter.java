package com.commercefoundry.domainlayer.order.adapter.in.web.presenter;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.CustomOptionItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderAmountsItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.ShippingFeeItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderQuoteResponse;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.ShippingFeeQuote;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 주문서 금액 → 고객 응답. 식별자 {@code long → String}, {@code Money → MoneyItem} 을 여기서 바꾼다.
 * 항목 · 금액 · 배송비 모양은 주문 생성 응답과 같다 — 생성 Presenter 가 아래 공개 메서드를 그대로 쓴다.
 */
@Component
public class OrderQuoteWebPresenter {

    public OrderQuoteResponse toResponse(OrderQuote quote) {
        return OrderQuoteResponse.builder()
            .currency(quote.currency().name())
            .items(toLineItems(quote.lines()))
            .amounts(toAmountsItem(quote))
            .shipping(toShippingFeeItem(quote.shippingFeeQuote()))
            .build();
    }

    public List<OrderLineItem> toLineItems(List<OrderQuoteLine> lines) {
        return lines.stream().map(OrderQuoteWebPresenter::toLineItem).toList();
    }

    /** 상품 합계 · 배송비 · 총액. */
    public OrderAmountsItem toAmountsItem(OrderQuote quote) {
        return OrderAmountsItem.builder()
            .itemsAmount(toMoneyItem(quote.itemsAmount()))
            .shippingFee(toMoneyItem(quote.shippingFeeQuote().shippingFee()))
            .totalAmount(toMoneyItem(quote.totalAmount()))
            .build();
    }

    public ShippingFeeItem toShippingFeeItem(ShippingFeeQuote shippingFee) {
        return ShippingFeeItem.builder()
            .scope(shippingFee.scope().name())
            .baseFee(toMoneyItem(shippingFee.baseFee()))
            .freeShippingApplied(shippingFee.freeShippingApplied())
            .remoteAreaSurcharge(toMoneyItem(shippingFee.remoteAreaSurcharge()))
            .remoteAreaName(shippingFee.remoteAreaName())
            .freeShippingThreshold(toMoneyItem(shippingFee.freeShippingThreshold()))
            .weightFee(toMoneyItem(shippingFee.weightFee()))
            .shippingZoneName(shippingFee.shippingZoneName())
            .build();
    }

    /** null 이면 null 이다 (무료배송 기준이 없는 정책 · 국내의 무게 요금). common-core 는 shared-commerce 를 모르므로 변환을 여기 둔다. */
    public static MoneyItem toMoneyItem(Money money) {
        return money == null ? null : MoneyItem.of(money.amount(), money.currency().name());
    }

    private static OrderLineItem toLineItem(OrderQuoteLine line) {
        return OrderLineItem.builder()
            .skuId(String.valueOf(line.skuId()))
            .productId(String.valueOf(line.productId()))
            .productName(line.productName())
            .optionName(line.optionName())
            .unitPrice(toMoneyItem(line.unitPrice()))
            .quantity(line.quantity())
            .lineAmount(toMoneyItem(line.lineAmount()))
            .customOrder(line.customOrder())
            .customOptions(line.customOptions().values().stream().map(OrderQuoteWebPresenter::toCustomOptionItem).toList())
            .build();
    }

    private static CustomOptionItem toCustomOptionItem(OrderCustomOption option) {
        return CustomOptionItem.builder()
            .fieldKey(option.fieldKey())
            .value(option.value())
            .build();
    }
}
