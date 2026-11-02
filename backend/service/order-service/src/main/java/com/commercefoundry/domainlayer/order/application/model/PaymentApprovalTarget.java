package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment.Result;

/** 이미 처리됐거나, 거절 대상이거나, 재고를 확정할 주문. */
public record PaymentApprovalTarget(boolean processed, Order order, Result rejection) {
}
