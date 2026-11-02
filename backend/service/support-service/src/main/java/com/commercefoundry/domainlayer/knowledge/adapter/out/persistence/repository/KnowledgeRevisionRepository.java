package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity.KnowledgeRevisionEntity;
import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository.custom.KnowledgeSearchCustomRepository;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KnowledgeRevisionRepository extends JpaRepository<KnowledgeRevisionEntity, Long>,
    KnowledgeSearchCustomRepository {

    Optional<KnowledgeRevisionEntity> findByDocumentIdAndRevisionNumber(long documentId, int revisionNumber);

    @Query("""
        select r from KnowledgeRevisionEntity r, KnowledgeDocumentEntity d
        where r.documentId = d.id and r.revisionNumber = d.publishedRevision
          and r.status = com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus.PUBLISHED
          and d.locale = :locale and r.validFrom <= :now and (r.validTo is null or :now < r.validTo)
        order by d.id desc
        """)
    List<KnowledgeRevisionEntity> findPublished(KnowledgeLocale locale, Instant now, Pageable pageable);
}
