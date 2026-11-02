package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.QPaymentEntity.paymentEntity;

import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
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
            criteria.status() == null ? null : paymentEntity.status.eq(criteria.status()),
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
}
