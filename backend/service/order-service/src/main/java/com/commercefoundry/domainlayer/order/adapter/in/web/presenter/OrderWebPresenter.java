package com.commercefoundry.domainlayer.order.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.ShippingAddressItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderCreateResponse;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 주문 → 고객 응답. 항목 · 금액 · 배송비 모양은 미리보기와 같다 — {@link OrderQuoteWebPresenter} 의 변환을 그대로 쓴다. 식별자 {@code long → String}, 상태 enum → metadata 를 여기서 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class OrderWebPresenter {

    private final OrderQuoteWebPresenter orderQuoteWebPresenter;

    public OrderCreateResponse toResponse(Order order) {
        return OrderCreateResponse.builder()
            .orderId(String.valueOf(order.id()))
            .status(order.status().toMetadata())
            .paymentDueAt(order.paymentDueAt())
            .currency(order.quote().currency().name())
            .items(orderQuoteWebPresenter.toLineItems(order.quote().lines()))
            .amounts(orderQuoteWebPresenter.toAmountsItem(order.quote()))
            .shipping(orderQuoteWebPresenter.toShippingFeeItem(order.quote().shippingFeeQuote()))
            .shippingAddress(toShippingAddressItem(order.shippingAddress()))
            .createdAt(order.createdAt())
            .build();
    }

    private static ShippingAddressItem toShippingAddressItem(ShippingAddress address) {
        return ShippingAddressItem.builder()
            .recipientName(address.recipientName())
            .recipientPhone(address.recipientPhone())
            .countryCode(address.countryCode())
            .postalCode(address.postalCode())
            .addressLine1(address.addressLine1())
            .addressLine2(address.addressLine2())
            .deliveryMemo(address.deliveryMemo())
            .build();
    }
}
