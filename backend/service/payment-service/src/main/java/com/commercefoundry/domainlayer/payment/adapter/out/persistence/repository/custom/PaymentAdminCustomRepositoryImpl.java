package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.QPaymentEntity.paymentEntity;
import static com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.QPaymentCancelEntity.paymentCancelEntity;

import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminStatusFilter;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminExternalRefundQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.JPAExpressions;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/** 독립 조회 저장소다. 기존 결제 쓰기 JpaRepository 의 빈 구성 · 저장 경로를 바꾸지 않는다. */
@Repository
@RequiredArgsConstructor
public class PaymentAdminCustomRepositoryImpl implements PaymentAdminCustomRepository {
    /** 외부 확인 환불 상태(#693). 판정은 도메인 {@link PaymentCancel.Status#refundedExternally()} 하나로만 한다. */
    private static final List<PaymentCancel.Status> EXTERNAL_REFUND_STATUSES =
        Arrays.stream(PaymentCancel.Status.values()).filter(PaymentCancel.Status::refundedExternally).toList();

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<PaymentAdminQueryResult> search(PaymentAdminSearchCriteria criteria) {
        PageRequest page = PageRequest.of(criteria.page(), criteria.size());
        BooleanExpression[] predicates = {
            statusPredicate(criteria.status()),
            criteria.orderId() == null ? null : paymentEntity.orderId.eq(criteria.orderId()),
            criteria.orderSynced() == null ? null : paymentEntity.orderSynced.eq(criteria.orderSynced())
        };
        List<PaymentAdminQueryResult> rows = queryFactory.select(Projections.constructor(PaymentAdminQueryResult.class,
                paymentEntity.id, paymentEntity.orderId, paymentEntity.status, paymentEntity.totalAmount, paymentEntity.totalCurrency,
                paymentEntity.method, paymentEntity.unknownReason, paymentEntity.orderSynced, paymentEntity.orderSyncFailureCode,
                paymentEntity.createdAt, paymentEntity.updatedAt, paymentEntity.approvalStartedAt, paymentEntity.approvedAt))
            .from(paymentEntity).where(predicates).orderBy(paymentEntity.id.desc()).offset(page.getOffset()).limit(page.getPageSize()).fetch();
        // 빈 후속 페이지도 목록과 같은 조건의 전체 건수를 돌려준다. Facade 의 같은 스냅숏에서 읽는다.
        Long count = queryFactory.select(paymentEntity.count()).from(paymentEntity).where(predicates).fetchOne();
        return new PageImpl<>(rows, page, count == null ? 0 : count);
    }

    @Override
    public List<PaymentAdminExternalRefundQueryResult> findExternalRefundTotals(Collection<Long> paymentIds) {
        // 페이지의 결제 아이디(최대 100개)를 IN 한 번으로 묶는다 — idx_payment_cancel_payment_id. 행 단위 조회(N+1)를 하지 않는다.
        // SUM(bigint)의 DB별 반환 타입에 기대지 않는다. DB에서 decimal로 합산하고 정확한 long 변환만 허용한다(승인 집계와 같은 방식).
        NumberExpression<BigDecimal> amount = Expressions.numberTemplate(BigDecimal.class, "sum(cast({0} as BigDecimal))", paymentCancelEntity.amount);
        return queryFactory.select(paymentCancelEntity.paymentId, paymentCancelEntity.status, paymentCancelEntity.currency, amount)
            .from(paymentCancelEntity)
            .where(paymentCancelEntity.paymentId.in(paymentIds), paymentCancelEntity.status.in(EXTERNAL_REFUND_STATUSES))
            .groupBy(paymentCancelEntity.paymentId, paymentCancelEntity.status, paymentCancelEntity.currency)
            .fetch().stream()
            .map(row -> new PaymentAdminExternalRefundQueryResult(row.get(paymentCancelEntity.paymentId), row.get(paymentCancelEntity.status),
                row.get(amount).longValueExact(), row.get(paymentCancelEntity.currency)))
            .toList();
    }

    private BooleanExpression statusPredicate(PaymentAdminStatusFilter status) {
        if (status == null) {
            return null;
        }
        if (status == PaymentAdminStatusFilter.REFUND_FAILED) {
            // 거절 이력의 존재만 읽는다. 다른 클레임의 후속 성공이나 현재 결제 상태로 과거 이력을 지우지 않는다.
            // 거절 행이 외부 확인 환불(#693)로 바뀌면 그 행은 더 이상 REJECTED 가 아니므로 저절로 빠진다.
            return cancelHistoryExists(paymentCancelEntity.status.eq(PaymentCancel.Status.REJECTED));
        }
        if (status == PaymentAdminStatusFilter.EXTERNAL_REFUNDED) {
            // 외부 확인 환불 원장의 존재만 읽는다. 결제 상태(OFF_PG 는 결제 상태에 반영하지 않는다)와 무관하다.
            return cancelHistoryExists(paymentCancelEntity.status.in(EXTERNAL_REFUND_STATUSES));
        }
        return paymentEntity.status.eq(PaymentStatus.valueOf(status.name()));
    }

    private static BooleanExpression cancelHistoryExists(BooleanExpression statusCondition) {
        return JPAExpressions.selectOne().from(paymentCancelEntity).where(paymentCancelEntity.paymentId.eq(paymentEntity.id), statusCondition).exists();
    }
}
