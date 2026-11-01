package com.commercefoundry.domainlayer.order.adapter.in.admin.presenter;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminAmountsItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminCustomOptionItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminShippingAddressItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderStatusChangeResultItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderStatusHistoryAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderStatusBulkChangeResponse;
import com.commercefoundry.domainlayer.order.application.info.OrderAdminSummaryInfo;
import com.commercefoundry.domainlayer.order.application.info.OrderStatusHistoryInfo;
import com.commercefoundry.domainlayer.order.application.model.OrderAdminDetail;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.shared.commerce.Money;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 어드민 주문 응답 (#17). 아이디는 문자열, 상태는 {@code {code, name, description}}, 배송지 개인정보는 {@link OrderAdminMasking} 으로 가린다.
 * 고객 주문 응답과 DTO · 변환을 나눈다 — 진입마다 자기 계약이다(어드민에는 마스킹 · 이력이 있고 배송비 산식은 없다).
 */
@Component
public class OrderAdminPresenter {

    public PageResponse<OrderAdminItem> toPageResponse(Page<OrderAdminSummaryInfo> page) {
        return PageResponse.of(page.map(OrderAdminPresenter::toItem));
    }

    public OrderAdminResponse toResponse(OrderAdminDetail detail) {
        Order order = detail.order();
        OrderQuote quote = order.quote();
        return OrderAdminResponse.builder()
            .orderId(String.valueOf(order.id()))
            .memberId(String.valueOf(order.memberId()))
            .status(order.status().toMetadata())
            .nextStatuses(detail.nextStatuses().stream().sorted(Comparator.comparing(OrderStatus::ordinal)).map(OrderStatus::toMetadata).toList())
            .currency(quote.currency().name())
            .items(quote.lines().stream().map(OrderAdminPresenter::toLineItem).toList())
            .amounts(new OrderAdminAmountsItem(money(quote.itemsAmount()), money(quote.shippingFeeQuote().shippingFee()), money(quote.totalAmount())))
            .shippingAddress(toMaskedAddress(order.shippingAddress()))
            .histories(detail.histories().stream().map(OrderAdminPresenter::toHistoryItem).toList())
            .paymentDueAt(order.paymentDueAt())
            .createdAt(order.createdAt())
            .build();
    }

    public OrderStatusBulkChangeResponse toBulkResponse(List<OrderStatusChangeResultItem> results) {
        int changed = (int) results.stream().filter(result -> "CHANGED".equals(result.result())).count();
        int unchanged = (int) results.stream().filter(result -> "UNCHANGED".equals(result.result())).count();
        return new OrderStatusBulkChangeResponse(changed, unchanged, results.size() - changed - unchanged, results);
    }

    private static OrderAdminItem toItem(OrderAdminSummaryInfo summary) {
        return OrderAdminItem.builder()
            .orderId(String.valueOf(summary.orderId()))
            .memberId(String.valueOf(summary.memberId()))
            .status(summary.status().toMetadata())
            .orderName(orderName(summary.firstProductName(), summary.itemCount()))
            .totalAmount(summary.totalAmount())
            .currency(summary.currency().name())
            .recipientName(OrderAdminMasking.maskName(summary.recipientName()))
            .createdAt(summary.createdAt())
            .paymentDueAt(summary.paymentDueAt())
            .build();
    }

    /** "첫 상품명" 또는 "첫 상품명 외 N건". */
    static String orderName(String firstProductName, int itemCount) {
        if (firstProductName == null) {
            return null;
        }
        return itemCount > 1 ? firstProductName + " 외 " + (itemCount - 1) + "건" : firstProductName;
    }

    private static OrderAdminLineItem toLineItem(OrderQuoteLine line) {
        return OrderAdminLineItem.builder()
            .skuId(String.valueOf(line.skuId()))
            .productId(String.valueOf(line.productId()))
            .productName(line.productName())
            .optionName(line.optionName())
            .unitPrice(money(line.unitPrice()))
            .quantity(line.quantity())
            .lineAmount(money(line.lineAmount()))
            .customOrder(line.customOrder())
            .customOptions(line.customOptions().values().stream().map(option -> new OrderAdminCustomOptionItem(option.fieldKey(), option.value())).toList())
            .build();
    }

    private static OrderStatusHistoryAdminItem toHistoryItem(OrderStatusHistoryInfo history) {
        return OrderStatusHistoryAdminItem.builder()
            .fromStatus(history.fromStatus() == null ? null : history.fromStatus().toMetadata())
            .toStatus(history.toStatus().toMetadata())
            .changedBy(history.changedByType().toMetadata())
            .changedById(history.changedById() == null ? null : String.valueOf(history.changedById()))
            .reason(history.reason())
            .changedAt(history.changedAt())
            .build();
    }

    private static OrderAdminShippingAddressItem toMaskedAddress(ShippingAddress address) {
        return OrderAdminShippingAddressItem.builder()
            .recipientName(OrderAdminMasking.maskName(address.recipientName()))
            .recipientPhone(OrderAdminMasking.maskPhone(address.recipientPhone()))
            .countryCode(address.countryCode())
            .postalCode(address.postalCode())
            .addressLine1(address.addressLine1())
            .addressLine2(OrderAdminMasking.maskIfPresent(address.addressLine2()))
            .city(address.city())
            .stateProvince(address.stateProvince())
            .deliveryMemo(OrderAdminMasking.maskIfPresent(address.deliveryMemo()))
            .build();
    }

    private static MoneyItem money(Money money) {
        return money == null ? null : MoneyItem.of(money.amount(), money.currency().name());
    }
}
