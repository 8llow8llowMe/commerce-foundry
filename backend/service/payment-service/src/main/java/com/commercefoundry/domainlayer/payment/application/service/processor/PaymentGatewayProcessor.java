package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.model.PgApprovalOutcome;
import com.commercefoundry.domainlayer.payment.application.model.PgApprovalRequest;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancelResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgRetentionQueryResult;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.commercefoundry.domainlayer.payment.application.model.PaymentCancellationInfo;

/**
 * PG 승인 요청. <b>트랜잭션을 걸지 않는다</b> — DB 커넥션을 쥔 채 PG 응답을 기다리지 않고, PG 승인 뒤 롤백으로 기록이 사라지지 않게
 * ({@code payment-guide.md} §3-3). IN_PROGRESS 기록은 이 호출 전에 커밋돼 있어야 한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentGatewayProcessor {

    private final PaymentGatewayPort paymentGatewayPort;

    /** 같은 클레임의 멱등 키로 PG 취소를 요청한다. DB 트랜잭션 밖에서만 호출한다. */
    public CancelOutcome cancel(Payment payment, long claimId, Money amount, Money cumulative) {
        PgCancelResult result = paymentGatewayPort.cancel(payment, claimId, amount, cumulative);
        return switch (result) {
            case PgCancelResult.Succeeded ignored -> CancelOutcome.SUCCEEDED;
            case PgCancelResult.Rejected ignored -> CancelOutcome.REJECTED;
            case PgCancelResult.Unknown ignored -> CancelOutcome.UNKNOWN;
        };
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentCancellationInfo cancelAutomatic(Payment payment, String pgKey, Money amount) {
        var result = paymentGatewayPort.cancelAutomatic(payment, pgKey, amount);
        return new PaymentCancellationInfo(PaymentCancellationInfo.State.valueOf(result.state().name()), result.transactionKey());
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentCancellationInfo inquireCancellation(Payment payment, Money amount, String pgKey, String transactionKey) {
        var result = paymentGatewayPort.inquireCancellation(payment, amount, pgKey, transactionKey);
        return new PaymentCancellationInfo(PaymentCancellationInfo.State.valueOf(result.state().name()), result.transactionKey());
    }

    /**
     * PG 잔액이 원장 기대 잔액과 같은지 결제키 GET 한 번으로 본다 (#691 무환불 판정, {@code payment-guide.md} §8-1). DB 트랜잭션 밖에서만 호출한다.
     * 자동 재시도에 붙이지 않는다 — 승인과 같은 {@code toss} 서킷을 쓴다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public RetentionOutcome inquireRetention(Payment payment, Money expectedBalance) {
        PgRetentionQueryResult result = paymentGatewayPort.inquireRetention(payment, expectedBalance);
        if (result == null || result.state() == null) {
            return RetentionOutcome.UNKNOWN;
        }
        return switch (result.state()) {
            case MATCHED -> RetentionOutcome.MATCHED;
            case MISMATCHED -> RetentionOutcome.MISMATCHED;
            case UNKNOWN -> RetentionOutcome.UNKNOWN;
            case UNSUPPORTED -> RetentionOutcome.UNSUPPORTED;
        };
    }

    public enum CancelOutcome { SUCCEEDED, REJECTED, UNKNOWN }

    /** {@code MATCHED} 만 무환불 확정의 근거다. {@code UNSUPPORTED} 는 대조 대상이 아닌 결제(PG 미호출)다. */
    public enum RetentionOutcome { MATCHED, MISMATCHED, UNKNOWN, UNSUPPORTED }

    /**
     * IN_PROGRESS 로 저장한 결제 시도의 승인을 요청한다. PG 멱등 키는 결제 시도 아이디라 같은 시도의 재요청(되돌린 READY 의 재승인 포함)이 같은 값을 싣는다 —
     * 고객의 {@code Idempotency-Key} 와 다르다.
     */
    public PgApprovalOutcome approve(Payment inProgress) {
        PgApprovalQueryResult result = paymentGatewayPort.approve(PgApprovalRequest.of(inProgress));
        return switch (result) {
            case PgApprovalQueryResult.Approved approved -> new PgApprovalOutcome.Approved(approved.approval());
            case PgApprovalQueryResult.Rejected rejected -> new PgApprovalOutcome.Rejected(rejected.failureCode(), rejected.failureMessage());
            case PgApprovalQueryResult.Unknown unknown -> new PgApprovalOutcome.Unknown(unknown.reason());
            case PgApprovalQueryResult.NotSent notSent -> new PgApprovalOutcome.NotSent(notSent.reason().name());
        };
    }
}
