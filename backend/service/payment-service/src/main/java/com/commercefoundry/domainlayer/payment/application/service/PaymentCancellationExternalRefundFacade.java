package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelExternalRefundResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Channel;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationExternalRefundUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessor.Candidate;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessor.Decided;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelExternalRefundProcessor.Verified;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 확인 환불 기록 (#693, {@code payment-guide.md} §8). PG 잔액 조회가 끼어 있어 Facade 에 트랜잭션을 걸지 않는다(NEVER) — 무환불 판정과 같이
 * 결제 행 잠금을 쥔 채 PG 응답을 기다리지 않게 스냅숏(tx1) → PG 조회(트랜잭션 밖) → 재확인 · 기록(tx2) 으로 나눈다.
 * 검증이 먼저고 기록이 뒤다 — PG 관측 잔액이 신고 채널을 설명하지 못하면 쓰지 않는다. 판정 규칙은 Processor · 후보 · 도메인에 있고, 여기서는 순서만 잇는다.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
@RequiredArgsConstructor
public class PaymentCancellationExternalRefundFacade implements PaymentCancellationExternalRefundUseCase {

    private final PaymentCancelExternalRefundProcessor processor;
    private final PaymentGatewayProcessor gateway;
    private final PaymentInternalPresenter presenter;

    @Override
    public PaymentCancelExternalRefundResponse recordExternalRefund(
        long paymentId, long orderId, long claimId, long amount, CurrencyCode currency, Channel channel
    ) {
        // 결제 후 취소 · 무환불 판정과 같다 — 양수가 아닌 claimId 는 DB · PG 전에 키 재사용으로 거절한다
        if (claimId <= 0) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        return presenter.toExternalRefundResponse(decide(paymentId, orderId, claimId, Money.of(amount, currency), channel));
    }

    private PaymentCancelExternalRefundInfo decide(long paymentId, long orderId, long claimId, Money amount, Channel channel) {
        return switch (processor.inspect(paymentId, orderId, claimId, amount, channel)) {
            case Decided decided -> decided.info();
            case Candidate candidate -> verify(candidate);
        };
    }

    private PaymentCancelExternalRefundInfo verify(Candidate candidate) {
        return switch (candidate.resolve(gateway.inquireRetention(candidate.payment(), candidate.expectedBalance()))) {
            case Decided decided -> decided.info();
            case Verified verified -> processor.record(verified);
        };
    }
}
