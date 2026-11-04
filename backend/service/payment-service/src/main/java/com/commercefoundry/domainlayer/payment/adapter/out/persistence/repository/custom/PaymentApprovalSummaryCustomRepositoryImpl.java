package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.QPaymentEntity.paymentEntity;

import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentApprovalSummaryQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.Money;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 독립 집계 저장소. 기존 쓰기 저장소 · 엔티티 · 승인 실행 경로는 바꾸지 않는다. */
@Repository
@RequiredArgsConstructor
public class PaymentApprovalSummaryCustomRepositoryImpl implements PaymentApprovalSummaryCustomRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    public List<PaymentApprovalSummaryQueryResult> summarize(Instant from, Instant to) {
        NumberExpression<Long> count = paymentEntity.id.count();
        // SUM(bigint)의 DB별 반환 타입에 기대지 않는다. DB에서 decimal로 합산하고 정확한 long 변환만 허용한다.
        NumberExpression<BigDecimal> amount = Expressions.numberTemplate(BigDecimal.class, "sum(cast({0} as BigDecimal))", paymentEntity.totalAmount);
        return queryFactory.select(paymentEntity.totalCurrency, paymentEntity.method, count, amount).from(paymentEntity)
            .where(paymentEntity.approvedAt.goe(from), paymentEntity.approvedAt.lt(to),
                paymentEntity.status.in(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED, PaymentStatus.CANCELED),
                paymentEntity.failureCode.isNull(), paymentEntity.abortReason.isNull())
            .groupBy(paymentEntity.totalCurrency, paymentEntity.method)
            .orderBy(paymentEntity.totalCurrency.asc(), paymentEntity.method.asc())
            .setHint("jakarta.persistence.query.timeout", 2000).fetch().stream()
            .map(row -> new PaymentApprovalSummaryQueryResult(row.get(paymentEntity.method), row.get(count),
                Money.of(row.get(amount).longValueExact(), row.get(paymentEntity.totalCurrency)))).toList();
    }
}
