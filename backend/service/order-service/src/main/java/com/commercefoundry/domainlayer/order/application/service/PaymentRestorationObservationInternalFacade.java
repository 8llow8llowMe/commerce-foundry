package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.PaymentRestorationObservationInternalResponse;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.PaymentRestorationObservationInternalPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationObservationInternalUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentRestorationObservationProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 결제 반영 재고 복원 작업 관측 (#808). DB 조회 · 트랜잭션 실패는 503 {@code ORDER_047} 로 바꾼다 — 관측 불가를 0 건으로 보이지 않게.
 * 원인 메시지는 남기지 않고 예외 타입만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRestorationObservationInternalFacade implements PaymentRestorationObservationInternalUseCase {

    private final PaymentRestorationObservationProcessor processor;
    private final PaymentRestorationObservationInternalPresenter presenter;

    @Override
    public PaymentRestorationObservationInternalResponse observe() {
        // 트랜잭션 밖에서 감싸 Processor 프록시의 시작 · 커밋 실패도 같은 오류로 바꾼다.
        try {
            return presenter.toResponse(processor.observe());
        } catch (DataAccessException | TransactionException exception) {
            OrderErrorCode errorCode = OrderErrorCode.PAYMENT_RESTORATION_OBSERVATION_UNAVAILABLE;
            log.warn("payment restoration observation failed. code={}, causeType={}", errorCode.getCode(), exception.getClass().getSimpleName());
            throw new OrderException(errorCode);
        }
    }
}
