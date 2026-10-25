package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.QAdminAuditLogEntity.adminAuditLogEntity;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * 조건은 완전 일치다. {@code target} 은 {@code (target, id)} 인덱스, 행위 · 관리자는 기존 {@code (조건, id)} 인덱스를 탄다.
 * 결과만 있으면 선택도가 낮아 PK 를 훑을 수 있다 — 한 계정의 로그인 실패는 {@code target} 과 함께 쓴다 (#286).
 */
@RequiredArgsConstructor
public class AdminAuditLogCustomRepositoryImpl implements AdminAuditLogCustomRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<AdminAuditLogEntity> findPage(String action, Long actorMemberId, String target, String result, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        BooleanExpression[] filters = filters(action, actorMemberId, target, result);
        List<AdminAuditLogEntity> content = queryFactory
            .selectFrom(adminAuditLogEntity)
            .where(filters)
            .orderBy(adminAuditLogEntity.id.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();
        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = queryFactory.select(adminAuditLogEntity.count()).from(adminAuditLogEntity).where(filters).fetchOne();
            return total == null ? 0L : total;
        });
    }

    private static BooleanExpression[] filters(String action, Long actorMemberId, String target, String result) {
        return new BooleanExpression[] {actionIs(action), actorIs(actorMemberId), targetIs(target), resultIs(result)};
    }

    private static BooleanExpression actionIs(String action) {
        return action == null ? null : adminAuditLogEntity.action.eq(action);
    }

    private static BooleanExpression actorIs(Long actorMemberId) {
        return actorMemberId == null ? null : adminAuditLogEntity.actorMemberId.eq(actorMemberId);
    }

    private static BooleanExpression targetIs(String target) {
        return target == null ? null : adminAuditLogEntity.target.eq(target);
    }

    private static BooleanExpression resultIs(String result) {
        return result == null ? null : adminAuditLogEntity.result.eq(result);
    }
}
