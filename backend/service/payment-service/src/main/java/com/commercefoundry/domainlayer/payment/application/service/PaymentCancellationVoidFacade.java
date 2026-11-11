package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelVoidResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelVoidInfo;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationVoidUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelVoidProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelVoidProcessor.Candidate;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelVoidProcessor.Decided;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor.RetentionOutcome;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 무환불 판정 (#691, {@code payment-guide.md} §8-1). PG 잔액 조회가 끼어 있어 Facade 에 트랜잭션을 걸지 않는다(NEVER) —
 * 결제 행 잠금을 쥔 채 PG 응답을 기다리지 않게, 스냅숏(tx1) → PG 조회(트랜잭션 밖) → 재확인 · 봉인(tx2) 으로 나눈다.
 * 확인이 먼저고 봉인이 뒤다 — PG 가 {@code MATCHED} 가 아니면 봉인하지 않는다.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
@RequiredArgsConstructor
public class PaymentCancellationVoidFacade implements PaymentCancellationVoidUseCase {

    private final PaymentCancelVoidProcessor processor;
    private final PaymentGatewayProcessor gateway;
    private final PaymentInternalPresenter presenter;

    @Override
    public PaymentCancelVoidResponse voidCancellation(long paymentId, long orderId, long claimId, long amount, CurrencyCode currency) {
        // 결제 후 취소(PaymentCancellationFacade)와 같다 — 양수가 아닌 claimId 는 DB · PG 전에 키 재사용으로 거절한다
        if (claimId <= 0) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        return presenter.toVoidResponse(decide(paymentId, orderId, claimId, Money.of(amount, currency)));
    }

    private PaymentCancelVoidInfo decide(long paymentId, long orderId, long claimId, Money amount) {
        return switch (processor.inspect(paymentId, orderId, claimId, amount)) {
            case Decided decided -> decided.info();
            case Candidate candidate -> settle(candidate);
        };
    }

    private PaymentCancelVoidInfo settle(Candidate candidate) {
        RetentionOutcome outcome = gateway.inquireRetention(candidate.payment(), candidate.expectedBalance());
        return outcome == RetentionOutcome.MATCHED ? processor.seal(candidate) : candidate.unconfirmedBy(outcome);
    }
}
