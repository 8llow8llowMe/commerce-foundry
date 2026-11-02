package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.QPaymentEntity.paymentEntity;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRecoveryQueryPort.Candidate;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PaymentRecoveryCustomRepositoryImpl implements PaymentRecoveryCustomRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    @Transactional(readOnly = true)
    public List<Candidate> findCandidates(PaymentStatus status, Instant cutoff, Candidate after, int limit) {
        if (status != PaymentStatus.UNKNOWN && status != PaymentStatus.IN_PROGRESS && status != PaymentStatus.READY) {
            throw new IllegalArgumentException("unsupported recovery lane");
        }
        BooleanExpression cursor = after == null ? null : paymentEntity.approvalStartedAt.gt(after.approvalStartedAt())
            .or(paymentEntity.approvalStartedAt.eq(after.approvalStartedAt()).and(paymentEntity.id.gt(after.id())));
        return queryFactory.select(Projections.constructor(Candidate.class, paymentEntity.id, paymentEntity.approvalStartedAt))
            .from(paymentEntity)
            .where(paymentEntity.gateway.eq(PaymentGateway.TOSS), paymentEntity.status.eq(status),
                paymentEntity.approvalStartedAt.loe(cutoff), paymentEntity.paymentKey.isNotNull(),
                status == PaymentStatus.UNKNOWN ? paymentEntity.unknownReason.ne(PaymentUnknownReason.MANUAL_REVIEW) : null, cursor)
            .orderBy(paymentEntity.approvalStartedAt.asc(), paymentEntity.id.asc()).limit(limit).fetch();
    }
}
