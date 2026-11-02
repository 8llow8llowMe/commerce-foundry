package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence;

import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository.KnowledgeDocumentRepository;
import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository.KnowledgeRevisionRepository;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeErrorCode;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeException;
import com.commercefoundry.domainlayer.knowledge.application.mapper.KnowledgeMapper;
import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeSearchCriteria;
import com.commercefoundry.domainlayer.knowledge.application.port.out.KnowledgeRepositoryPort;
import com.commercefoundry.domainlayer.knowledge.application.port.out.query.KnowledgeSearchQueryResult;
import com.commercefoundry.domainlayer.knowledge.application.port.out.query.KnowledgeSummaryQueryResult;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class KnowledgeRepositoryAdapter implements KnowledgeRepositoryPort {

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeRevisionRepository revisions;
    private final KnowledgeMapper mapper;

    @Override
    public List<KnowledgeSearchQueryResult> search(
        KnowledgeSearchCriteria criteria, Instant cutoff
    ) {
        return revisions.search(criteria, cutoff);
    }

    @Override
    public Page<KnowledgeSummaryQueryResult> findAdminSummaries(KnowledgeType type, KnowledgeLocale locale, int page, int size) {
        return documents.findAdminSummaries(type, locale, PageRequest.of(page, size));
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(long id) {
        return documents.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<KnowledgeRevision> findRevision(long documentId, int revisionNumber) {
        return revisions.findByDocumentIdAndRevisionNumber(documentId, revisionNumber).map(mapper::toDomain);
    }

    @Override
    public KnowledgeDocument saveDocument(KnowledgeDocument document) {
        return guarded(() -> mapper.toDomain(documents.saveAndFlush(mapper.toEntity(document))));
    }

    @Override
    public KnowledgeRevision saveRevision(KnowledgeRevision revision) {
        return guarded(() -> mapper.toDomain(revisions.saveAndFlush(mapper.toEntity(revision))));
    }

    @Override
    public List<KnowledgeRevision> findPublished(KnowledgeLocale locale, Instant now, int limit) {
        if (limit < 1 || limit > 100) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_INPUT);
        }
        return revisions.findPublished(locale, now, PageRequest.of(0, limit)).stream().map(mapper::toDomain).toList();
    }

    private <T> T guarded(Supplier<T> save) {
        try {
            return save.get();
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, "uk_knowledge_revision_document_id_revision_number")) {
                throw new KnowledgeException(KnowledgeErrorCode.CONCURRENTLY_CHANGED, exception);
            }
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            throw new KnowledgeException(KnowledgeErrorCode.CONCURRENTLY_CHANGED, exception);
        }
    }
}
