package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelResponse;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PG 취소는 두 짧은 DB 트랜잭션 사이에서 호출한다. */
@Service
@Transactional(propagation = Propagation.NEVER)
@RequiredArgsConstructor
public class PaymentCancellationFacade implements PaymentCancellationUseCase {

    private final PaymentCancelProcessor processor;
    private final PaymentGatewayProcessor gateway;

    @Override
    public PaymentCancelResponse cancel(long paymentId, long claimId, long amount, CurrencyCode currency, String idempotencyKey) {
        if (claimId <= 0 || !Long.toString(claimId).equals(idempotencyKey)) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        PaymentCancelProcessor.Prepared prepared = processor.prepare(paymentId, claimId, Money.of(amount, currency));
        // SUCCEEDED 와 외부 확인 환불(EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED, #693) 모두 환불 확정이다 — PG 를 다시 부르면 이중 환불이 된다.
        if (prepared.cancel().status().countsAsRefunded()) {
            return response(paymentId, claimId, prepared.payment().status().name(), amount);
        }
        if (prepared.cancel().status() != PaymentCancel.Status.PENDING) {
            // 준비가 PENDING 만 넘기지만, 새 상태가 생겨도 PG 를 여는 쪽으로 새지 않게 닫는다.
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        PaymentGatewayProcessor.CancelOutcome result = gateway.cancel(prepared.payment(), claimId, prepared.cancel().amount(), prepared.cumulative());
        if (result == PaymentGatewayProcessor.CancelOutcome.SUCCEEDED) {
            return response(paymentId, claimId, processor.complete(paymentId, claimId).status().name(), amount);
        }
        if (result == PaymentGatewayProcessor.CancelOutcome.REJECTED) {
            processor.reject(paymentId, claimId);
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        throw new PaymentException(PaymentErrorCode.CANCELLATION_UNCONFIRMED);
    }

    private static PaymentCancelResponse response(long paymentId, long claimId, String status, long amount) {
        return new PaymentCancelResponse(Long.toString(paymentId), Long.toString(claimId), status, amount);
    }
}
