package com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity.QLegalDocumentEntity.legalDocumentEntity;
import static com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity.QLegalDocumentRevisionEntity.legalDocumentRevisionEntity;

import com.commercefoundry.domainlayer.legaldocument.application.port.out.query.LegalDocumentQueryResults;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;

public class LegalDocumentPublishedCustomRepositoryImpl implements LegalDocumentPublishedCustomRepository {
    private final JPAQueryFactory factory;

    public LegalDocumentPublishedCustomRepositoryImpl(EntityManager entityManager) {
        factory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<LegalDocumentQueryResults.Published> published(String locale) {
        var d = legalDocumentEntity;
        var r = legalDocumentRevisionEntity;
        return query().where(d.locale.eq(locale), r.revisionNumber.eq(d.publishedRevision))
            .orderBy(d.type.asc()).fetch();
    }

    @Override
    public Optional<LegalDocumentQueryResults.Published> published(LegalDocumentKey key, Integer revisionNumber) {
        var d = legalDocumentEntity;
        var r = legalDocumentRevisionEntity;
        var revisionFilter = revisionNumber == null ? r.revisionNumber.eq(d.publishedRevision) : r.revisionNumber.eq(revisionNumber);
        return Optional.ofNullable(query().where(d.type.eq(key.type()), d.locale.eq(key.locale()), revisionFilter).fetchOne());
    }

    private JPAQuery<LegalDocumentQueryResults.Published> query() {
        var d = legalDocumentEntity;
        var r = legalDocumentRevisionEntity;
        return factory.select(Projections.constructor(LegalDocumentQueryResults.Published.class,
                d.type.stringValue(), d.locale, r.revisionNumber, r.title, r.body, r.publishedAt))
            .from(d).join(r).on(r.documentId.eq(d.id))
            .where(r.publishedAt.isNotNull(), r.publishedBy.isNotNull());
    }
}
