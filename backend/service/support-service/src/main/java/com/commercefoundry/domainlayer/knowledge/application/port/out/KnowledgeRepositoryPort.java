package com.commercefoundry.domainlayer.knowledge.application.port.out;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRepositoryPort {

    Optional<KnowledgeDocument> findDocument(long id);

    Optional<KnowledgeRevision> findRevision(long documentId, int revisionNumber);

/** 지식 게시 정책의 불변식과 버전 계약을 적용한다. */
    KnowledgeDocument saveDocument(KnowledgeDocument document);

    KnowledgeRevision saveRevision(KnowledgeRevision revision);

/** 지식 게시 정책의 불변식과 버전 계약을 적용한다. */
    List<KnowledgeRevision> findPublished(KnowledgeLocale locale, Instant now, int limit);
}
