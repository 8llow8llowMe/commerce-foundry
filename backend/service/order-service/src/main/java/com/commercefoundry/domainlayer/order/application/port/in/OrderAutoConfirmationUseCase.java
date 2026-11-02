package com.commercefoundry.domainlayer.order.application.port.in;

/** 자동 구매 확정 스윕 진입점. */
public interface OrderAutoConfirmationUseCase {
    void confirmDeliveredOrders();
}
