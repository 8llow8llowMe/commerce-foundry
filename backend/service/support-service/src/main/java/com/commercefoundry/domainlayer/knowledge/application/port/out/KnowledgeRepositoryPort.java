package com.commercefoundry.domainlayer.knowledge.application.port.out;

import com.commercefoundry.domainlayer.knowledge.application.port.out.query.KnowledgeSummaryQueryResult;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

public interface KnowledgeRepositoryPort {

    Page<KnowledgeSummaryQueryResult> findAdminSummaries(KnowledgeType type, KnowledgeLocale locale, int page, int size);

    Optional<KnowledgeDocument> findDocument(long id);

    Optional<KnowledgeRevision> findRevision(long documentId, int revisionNumber);

    KnowledgeDocument saveDocument(KnowledgeDocument document);

    KnowledgeRevision saveRevision(KnowledgeRevision revision);

    List<KnowledgeRevision> findPublished(KnowledgeLocale locale, Instant now, int limit);
}
