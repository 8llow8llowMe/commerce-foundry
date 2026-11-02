package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.global.properties.PaymentRecoveryProperties;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 건별 새 트랜잭션. 이 경계 안에서는 PG를 호출하지 않는다. */
@Component
@RequiredArgsConstructor
public class PaymentRecoveryProcessor {
    private final PaymentRepositoryPort repository;
    private final PaymentProcessor paymentProcessor;
    private final PaymentRecoveryProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Payment> prepare(long id, PaymentStatus lane, Instant tickNow) {
        // 건별 재조회는 동시 요청의 상태·버전을 검사한다. 목록 부가정보를 채우는 N+1 조회가 아니다.
        return repository.findById(id).filter(payment -> eligible(payment, lane, tickNow)).map(payment ->
            lane == PaymentStatus.IN_PROGRESS ? repository.save(payment.markUnknown(PaymentUnknownReason.STALE_IN_PROGRESS)) : payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Payment expected, PgInquiryQueryResult result, Instant tickNow) {
        Payment current = repository.findById(expected.id()).orElse(null);
        // 레코드 전체 비교는 소유자·식별자·금액·시각·고객 멱등 키·버전을 모두 포함한다.
        if (current == null || current.status() == PaymentStatus.DONE || !expected.equals(current)) {
            return;
        }
        Payment next = apply(current, result, tickNow);
        if (next == current) {
            return;
        }
        if (next.status() == PaymentStatus.DONE) {
            paymentProcessor.saveApproved(current, next);
        } else {
            repository.save(next);
        }
    }

    private Payment apply(Payment current, PgInquiryQueryResult result, Instant now) {
        try {
            if (result instanceof PgInquiryQueryResult.Approved approved) {
                return unknown(current, PaymentUnknownReason.ALREADY_PROCESSED).resolve(approved.toInquiry());
            }
            if (result instanceof PgInquiryQueryResult.Failed failed) {
                if (!current.paymentKey().equals(failed.paymentKey())) {
                    return manual(current);
                }
                return current.status() == PaymentStatus.READY
                    ? current.abortAfterInquiry(failed.toInquiry(), PaymentAbortReason.RECOVERY_NOT_APPROVED)
                    : current.resolve(failed.toInquiry());
            }
            if (result instanceof PgInquiryQueryResult.Pending pending && !current.paymentKey().equals(pending.paymentKey())) {
                return manual(current);
            }
            if (result instanceof PgInquiryQueryResult.Unknown unknown
                && unknown.reason() != PaymentUnknownReason.TIMEOUT && unknown.reason() != PaymentUnknownReason.SERVER_ERROR) {
                return manual(current);
            }
            return !current.approvalStartedAt().plus(properties.manualReviewAfter()).isAfter(now) ? manual(current) : current;
        } catch (PaymentApprovalMismatchException mismatch) {
            return manual(current);
        }
    }

    private static Payment unknown(Payment payment, PaymentUnknownReason reason) {
        return payment.status() == PaymentStatus.READY ? payment.recoverReturnedReady(reason) : payment;
    }

    private static Payment manual(Payment payment) {
        return unknown(payment, PaymentUnknownReason.MANUAL_REVIEW).flagForManualReview(PaymentUnknownReason.MANUAL_REVIEW);
    }

    private boolean eligible(Payment payment, PaymentStatus lane, Instant now) {
        return payment.gateway() == PaymentGateway.TOSS && payment.status() == lane && payment.paymentKey() != null
            && payment.approvalStartedAt() != null && payment.unknownReason() != PaymentUnknownReason.MANUAL_REVIEW
            && !payment.approvalStartedAt().isAfter(now.minus(switch (lane) {
                case UNKNOWN -> properties.unknownInquiryAfter();
                case IN_PROGRESS -> properties.staleInProgressAfter();
                case READY -> properties.returnedReadyAfter();
                default -> throw new IllegalArgumentException("unsupported recovery lane");
            }));
    }
}
