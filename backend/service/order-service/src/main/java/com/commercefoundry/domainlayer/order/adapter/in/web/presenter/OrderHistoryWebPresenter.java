package com.commercefoundry.domainlayer.order.adapter.in.web.presenter;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderSummaryItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.application.info.OrderMemberSummaryInfo;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.persistence.dto.SliceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

/**
 * 고객 주문 내역 · 상세 (#16). 항목 · 금액 · 배송비 · 배송지 모양은 주문 생성 응답과 같다 — {@link OrderQuoteWebPresenter} · {@link OrderWebPresenter} 의 변환을 쓴다.
 * {@code cancelable} 은 application 이 판정한 값을 옮기기만 한다(#16 PR-2 — 결제 후 취소 게이트 · 제작 중 설정이 섞인 정책이다).
 */
@Component
@RequiredArgsConstructor
public class OrderHistoryWebPresenter {

    private final OrderQuoteWebPresenter orderQuoteWebPresenter;

    public SliceResponse<OrderSummaryItem> toSliceResponse(Slice<OrderMemberSummaryInfo> slice) {
        return SliceResponse.of(slice).map(OrderHistoryWebPresenter::toSummaryItem);
    }

    /** @param cancelable Processor 가 판정한 지금 취소 가능 여부 */
    public OrderDetailResponse toDetailResponse(Order order, boolean cancelable) {
        return OrderDetailResponse.builder()
            .orderId(String.valueOf(order.id()))
            .status(order.status().toMetadata())
            .cancelable(cancelable)
            .paymentDueAt(order.paymentDueAt())
            .currency(order.quote().currency().name())
            .items(orderQuoteWebPresenter.toLineItems(order.quote().lines()))
            .amounts(orderQuoteWebPresenter.toAmountsItem(order.quote()))
            .shipping(orderQuoteWebPresenter.toShippingFeeItem(order.quote().shippingFeeQuote()))
            .shippingAddress(OrderWebPresenter.toShippingAddressItem(order.shippingAddress()))
            .createdAt(order.createdAt())
            .build();
    }

    private static OrderSummaryItem toSummaryItem(OrderMemberSummaryInfo summary) {
        return OrderSummaryItem.builder()
            .orderId(String.valueOf(summary.orderId()))
            .status(summary.status().toMetadata())
            .orderName(orderName(summary.firstProductName(), summary.itemCount()))
            .itemCount(summary.itemCount())
            .totalAmount(MoneyItem.of(summary.totalAmount(), summary.currency().name()))
            .cancelable(summary.cancelable())
            .paymentDueAt(summary.paymentDueAt())
            .createdAt(summary.createdAt())
            .build();
    }

    /** "첫 상품명" 또는 "첫 상품명 외 N건". */
    static String orderName(String firstProductName, int itemCount) {
        if (firstProductName == null) {
            return null;
        }
        return itemCount > 1 ? firstProductName + " 외 " + (itemCount - 1) + "건" : firstProductName;
    }
}
