package com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence;

import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity.LegalDocumentEntity;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity.LegalDocumentRevisionEntity;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRepository;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRevisionRepository;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentErrorCode;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentException;
import com.commercefoundry.domainlayer.legaldocument.application.port.out.LegalDocumentRepositoryPort;
import com.commercefoundry.domainlayer.legaldocument.application.port.out.query.LegalDocumentQueryResults;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocument;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRevision;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRuleException;
import com.commercefoundry.persistence.util.ConstraintViolations;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LegalDocumentRepositoryAdapter implements LegalDocumentRepositoryPort {
    private final LegalDocumentRepository documents;
    private final LegalDocumentRevisionRepository revisions;
    private final EntityManager entityManager;

    @Override
    public List<LegalDocument> list() {
        return documents.findAll(Sort.by("type", "locale")).stream().map(LegalDocumentEntity::toDomain).toList();
    }

    @Override
    public Optional<LegalDocumentQueryResults.Detail> detail(LegalDocumentKey key) {
        return documents.findByTypeAndLocale(key.type(), key.locale()).map(entity -> {
            var document = entity.toDomain();
            return new LegalDocumentQueryResults.Detail(document, revision(document.id(), document.latestRevision()).toDomain());
        });
    }

    @Override
    public Page<LegalDocumentRevision> revisions(LegalDocumentKey key, Pageable pageable) {
        var document = document(key).toDomain();
        return revisions.findAllByDocumentId(document.id(), pageable).map(LegalDocumentRevisionEntity::toDomain);
    }

    @Override
    public List<LegalDocumentQueryResults.Published> published(String locale) {
        return documents.published(locale);
    }

    @Override
    public Optional<LegalDocumentQueryResults.Published> published(LegalDocumentKey key, Integer revisionNumber) {
        return documents.published(key, revisionNumber);
    }

    @Override
    @Transactional
    public LegalDocumentQueryResults.Detail saveDraft(LegalDocumentKey key, Long expectedVersion, String title, String body,
                                                       long actorMemberId, long newDocumentId, long revisionId, Instant now) {
        boolean creating = false;
        try {
            var found = documents.findByTypeAndLocale(key.type(), key.locale());
            creating = found.isEmpty();
            var root = found.map(LegalDocumentEntity::toDomain).orElseGet(() -> new LegalDocument(newDocumentId, key, 0, null, null));
            var next = root.append(expectedVersion);
            var entity = found.orElseGet(() -> LegalDocumentEntity.create(next));
            entity.apply(next);
            var saved = documents.saveAndFlush(entity).toDomain();
            var revision = new LegalDocumentRevision(revisionId, saved.id(), saved.latestRevision(), title, body, actorMemberId, now, null, null);
            entityManager.persist(LegalDocumentRevisionEntity.create(revision));
            entityManager.flush();
            return new LegalDocumentQueryResults.Detail(saved, revision);
        } catch (LegalDocumentRuleException exception) {
            throw rule(exception);
        } catch (OptimisticLockingFailureException exception) {
            throw new LegalDocumentException(LegalDocumentErrorCode.CONFLICT, exception);
        } catch (DataIntegrityViolationException exception) {
            if (creating && ConstraintViolations.violates(exception, "uk_legal_document_type_locale")) {
                throw new LegalDocumentException(LegalDocumentErrorCode.CONFLICT, exception);
            }
            throw exception;
        }
    }

    @Override
    @Transactional
    public LegalDocumentQueryResults.Detail publish(LegalDocumentKey key, Long expectedVersion, int revisionNumber,
                                                     boolean reviewConfirmed, long actorMemberId, Instant now) {
        try {
            var entity = document(key);
            var root = entity.toDomain();
            var next = root.publish(expectedVersion, revisionNumber);
            var revisionEntity = revision(root.id(), revisionNumber);
            if (next == root) {
                return new LegalDocumentQueryResults.Detail(root, revision(root.id(), root.latestRevision()).toDomain());
            }
            var published = revisionEntity.toDomain().publish(reviewConfirmed, actorMemberId, now);
            entity.apply(next);
            var saved = documents.saveAndFlush(entity).toDomain();
            revisionEntity.applyPublication(published);
            entityManager.flush();
            return new LegalDocumentQueryResults.Detail(saved, published);
        } catch (LegalDocumentRuleException exception) {
            throw rule(exception);
        } catch (OptimisticLockingFailureException exception) {
            throw new LegalDocumentException(LegalDocumentErrorCode.CONFLICT, exception);
        }
    }

    private LegalDocumentEntity document(LegalDocumentKey key) {
        return documents.findByTypeAndLocale(key.type(), key.locale())
            .orElseThrow(() -> new LegalDocumentException(LegalDocumentErrorCode.NOT_FOUND));
    }

    private LegalDocumentRevisionEntity revision(long documentId, int number) {
        return revisions.findByDocumentIdAndRevisionNumber(documentId, number)
            .orElseThrow(() -> new LegalDocumentException(LegalDocumentErrorCode.NOT_FOUND));
    }

    private LegalDocumentException rule(LegalDocumentRuleException exception) {
        var code = switch (exception.reason()) {
            case CONFLICT -> LegalDocumentErrorCode.CONFLICT;
            case REVIEW_REQUIRED -> LegalDocumentErrorCode.REVIEW_REQUIRED;
            case UNRESOLVED_PLACEHOLDER -> LegalDocumentErrorCode.UNRESOLVED_PLACEHOLDER;
        };
        return new LegalDocumentException(code);
    }
}
