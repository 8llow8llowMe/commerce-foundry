package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentInternalUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 간 결제 조회 (#308, {@code payment-guide.md} §6). DB 읽기 하나뿐이고 외부 I/O 가 없어 읽기 트랜잭션을 건다.
 * 결제 게이트({@code payment.enabled})를 보지 않는다 — 고객 상태 조회처럼 게이트가 닫혀 있어도 이미 있는 결제는 읽을 수 있어야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentInternalFacade implements PaymentInternalUseCase {

    private final PaymentProcessor paymentProcessor;
    private final PaymentInternalPresenter paymentInternalPresenter;

    @Override
    public PaymentInternalResponse getPayment(long paymentId) {
        return paymentInternalPresenter.toResponse(paymentProcessor.getUnscoped(paymentId));
    }
}
