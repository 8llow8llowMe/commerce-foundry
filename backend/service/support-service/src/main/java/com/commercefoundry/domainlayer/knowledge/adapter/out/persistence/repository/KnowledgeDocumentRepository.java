package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity.KnowledgeDocumentEntity;
import com.commercefoundry.domainlayer.knowledge.application.port.out.query.KnowledgeSummaryQueryResult;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {

    @Query(value = """
        select new com.commercefoundry.domainlayer.knowledge.application.port.out.query.KnowledgeSummaryQueryResult(
            d.id, d.type, d.locale, d.latestRevision, d.publishedRevision, d.version, r.version,
            r.title, r.category, r.status, r.validFrom, r.validTo, r.approvedBy, r.approvedAt, r.publishedAt)
        from KnowledgeDocumentEntity d join KnowledgeRevisionEntity r
            on r.documentId = d.id and r.revisionNumber = d.latestRevision
        where (:type is null or d.type = :type) and (:locale is null or d.locale = :locale)
        order by d.id desc
        """, countQuery = """
        select count(d.id) from KnowledgeDocumentEntity d join KnowledgeRevisionEntity r
            on r.documentId = d.id and r.revisionNumber = d.latestRevision
        where (:type is null or d.type = :type) and (:locale is null or d.locale = :locale)
        """)
    Page<KnowledgeSummaryQueryResult> findAdminSummaries(KnowledgeType type, KnowledgeLocale locale, Pageable pageable);
}
