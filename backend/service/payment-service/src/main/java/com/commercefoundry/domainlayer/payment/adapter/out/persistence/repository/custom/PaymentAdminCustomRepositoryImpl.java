package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.QPaymentEntity.paymentEntity;
import static com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.QPaymentCancelEntity.paymentCancelEntity;

import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminStatusFilter;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.JPAExpressions;
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

    private BooleanExpression statusPredicate(PaymentAdminStatusFilter status) {
        if (status == null) {
            return null;
        }
        if (status == PaymentAdminStatusFilter.REFUND_FAILED) {
            // 거절 이력의 존재만 읽는다. 다른 클레임의 후속 성공이나 현재 결제 상태로 과거 이력을 지우지 않는다.
            return JPAExpressions.selectOne().from(paymentCancelEntity)
                .where(paymentCancelEntity.paymentId.eq(paymentEntity.id), paymentCancelEntity.status.eq(PaymentCancel.Status.REJECTED)).exists();
        }
        return paymentEntity.status.eq(PaymentStatus.valueOf(status.name()));
    }
}
