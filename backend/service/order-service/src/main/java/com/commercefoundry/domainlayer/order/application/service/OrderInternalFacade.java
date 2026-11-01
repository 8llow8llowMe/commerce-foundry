package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentSummaryInternalResponse;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderPaymentSummaryInternalPresenter;
import com.commercefoundry.domainlayer.order.application.port.in.OrderInternalUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentSummaryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 서비스 간 주문 조회. 원격 호출이 없는 읽기라 Facade 에 읽기 트랜잭션을 건다 — 주문 · 배송지 · 항목 · 입력을 한 스냅숏으로 읽는다. */
@Service
@RequiredArgsConstructor
public class OrderInternalFacade implements OrderInternalUseCase {

    private final OrderProcessor orderProcessor;
    private final OrderPaymentSummaryProcessor orderPaymentSummaryProcessor;
    private final OrderPaymentSummaryInternalPresenter orderPaymentSummaryInternalPresenter;

    @Override
    @Transactional(readOnly = true)
    public OrderPaymentSummaryInternalResponse getPaymentSummary(long orderId, long memberId) {
        Order order = orderProcessor.getOwned(memberId, orderId);
        return orderPaymentSummaryInternalPresenter.toResponse(orderPaymentSummaryProcessor.summarize(order));
    }
}
