package com.commercefoundry.domainlayer.order.adapter.in.web.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderReturnResponse;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import org.springframework.stereotype.Component;

@Component
public class OrderReturnWebPresenter {
    public OrderReturnResponse toResponse(Claim claim) {
        var items = claim.items().stream().map(item -> new OrderReturnResponse.Item(String.valueOf(item.orderItemId()), item.quantity(), item.reason().toMetadata())).toList();
        return new OrderReturnResponse(String.valueOf(claim.id()), String.valueOf(claim.orderId()), claim.status().toMetadata(), items,
            MoneyItems.of(claim.expectedRefund()), claim.requestedAt());
    }
}
