package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.QClaimEntity.claimEntity;

import com.commercefoundry.domainlayer.order.application.port.out.query.ClaimAdminSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ClaimCustomRepositoryImpl implements ClaimCustomRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    public Page<ClaimAdminSummaryQueryResult> findAdminSummaryPage(Long orderId, Claim.Type type, Claim.Status status, Pageable pageable) {
        BooleanBuilder filter = new BooleanBuilder();
        if (orderId != null) {
            filter.and(claimEntity.orderId.eq(orderId));
        }
        if (type != null) {
            filter.and(claimEntity.type.eq(type));
        }
        if (status != null) {
            filter.and(claimEntity.status.eq(status));
        }
        List<ClaimAdminSummaryQueryResult> rows = queryFactory.select(Projections.constructor(ClaimAdminSummaryQueryResult.class,
                claimEntity.id, claimEntity.orderId, claimEntity.type, claimEntity.status,
                claimEntity.expectedRefundAmount, claimEntity.expectedRefundCurrency, claimEntity.createdAt))
            .from(claimEntity).where(filter).orderBy(claimEntity.id.desc()).offset(pageable.getOffset()).limit(pageable.getPageSize()).fetch();
        // 같은 조건으로 항상 실제 count를 조회하여 빈 페이지의 전체 건수도 보존한다.
        Long total = queryFactory.select(claimEntity.count()).from(claimEntity).where(filter).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }
}
