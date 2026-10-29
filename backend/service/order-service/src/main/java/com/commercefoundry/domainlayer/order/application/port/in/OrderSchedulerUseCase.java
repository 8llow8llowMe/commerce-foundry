package com.commercefoundry.domainlayer.order.application.port.in;

/** 주문 스케줄러 진입점 — 사람이 아니라 시스템이 부른다. */
public interface OrderSchedulerUseCase {

    /** 결제 기한이 지난 결제 대기 주문을 만료한다 (미결제 만료 스윕 한 번, {@code order-flow-guide.md} §1-3). */
    void expireOverdueOrders();
}
