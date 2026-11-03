package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentResultInternalResponse;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderPaymentResultInternalPresenter;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentResultInternalUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentResultProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 외부 I/O 없이 로컬 DB 한 건만 읽으므로 Facade 에 읽기 전용 트랜잭션을 둔다. */
@Service
@RequiredArgsConstructor
public class OrderPaymentResultInternalFacade implements OrderPaymentResultInternalUseCase {

    private final OrderPaymentResultProcessor orderPaymentResultProcessor;
    private final OrderPaymentResultInternalPresenter orderPaymentResultInternalPresenter;

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public OrderPaymentResultInternalResponse getResult(long paymentId) {
        return orderPaymentResultInternalPresenter.toResponse(orderPaymentResultProcessor.getResult(paymentId));
    }
}
