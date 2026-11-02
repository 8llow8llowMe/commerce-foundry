package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity.QKnowledgeDocumentEntity.knowledgeDocumentEntity;
import static com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity.QKnowledgeRevisionEntity.knowledgeRevisionEntity;

import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeSearchCriteria;
import com.commercefoundry.domainlayer.knowledge.application.port.out.query.KnowledgeSearchQueryResult;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;

public class KnowledgeSearchCustomRepositoryImpl implements KnowledgeSearchCustomRepository {
    private final JPAQueryFactory queryFactory;

    public KnowledgeSearchCustomRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<KnowledgeSearchQueryResult> search(KnowledgeSearchCriteria criteria, Instant cutoff) {
        var d = knowledgeDocumentEntity;
        var r = knowledgeRevisionEntity;
        BooleanBuilder filter = new BooleanBuilder(d.locale.eq(criteria.locale()));
        if (criteria.category() != null) {
            filter.and(r.category.eq(criteria.category()));
        }
        filter.and(r.status.eq(KnowledgeStatus.PUBLISHED)).and(r.approvedBy.isNotNull()).and(r.approvedAt.isNotNull())
            .and(r.validFrom.loe(cutoff)).and(r.validTo.isNull().or(r.validTo.gt(cutoff)));
        NumberExpression<Integer> score = Expressions.asNumber(0);
        for (String token : criteria.tokens()) {
            String pattern = literalPattern(token);
            score = score.add(new CaseBuilder().when(r.title.lower().like(pattern, '!')).then(3).otherwise(0))
                .add(new CaseBuilder().when(r.body.lower().like(pattern, '!')).then(1).otherwise(0));
        }
        return queryFactory.select(Projections.constructor(KnowledgeSearchQueryResult.class,
                d.id, r.id, r.revisionNumber, r.title, r.body, r.category, d.type, d.locale, score, r.validFrom, r.validTo))
            .from(d).join(r).on(r.documentId.eq(d.id).and(r.revisionNumber.eq(d.publishedRevision)))
            .where(filter, score.gt(0)).orderBy(score.desc(), d.id.desc()).limit(criteria.topN()).fetch();
    }

    static String literalPattern(String token) {
        return "%" + token.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
