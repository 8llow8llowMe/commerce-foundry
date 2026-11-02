package com.commercefoundry.domainlayer.knowledge.application.service.processor;

import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeErrorCode;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeException;
import com.commercefoundry.domainlayer.knowledge.application.port.out.KnowledgeRepositoryPort;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 지식 게시 정책의 불변식과 버전 계약을 적용한다. */
@Component
@RequiredArgsConstructor
@Transactional
public class KnowledgeProcessor {

    private final KnowledgeRepositoryPort repository;
    private final SnowflakeIdGenerator idGenerator;

    public KnowledgeRevision create(KnowledgeType type, KnowledgeLocale locale, KnowledgeRevision.Content content) {
        return validated(() -> {
            KnowledgeDocument document = new KnowledgeDocument(idGenerator.generateId(), type, locale, 1, null, null);
            repository.saveDocument(document);
            return repository.saveRevision(KnowledgeRevision.draft(idGenerator.generateId(), document.id(), 1, content));
        });
    }

    public KnowledgeRevision createDraft(long documentId, KnowledgeRevision.Content content) {
        return validated(() -> {
            KnowledgeDocument next = document(documentId).nextDraft();
            KnowledgeRevision revision = KnowledgeRevision.draft(idGenerator.generateId(), documentId, next.latestRevision(), content);
            repository.saveDocument(next);
            return repository.saveRevision(revision);
        });
    }

    public KnowledgeRevision edit(long documentId, int revisionNumber, Long expectedVersion, KnowledgeRevision.Content content) {
        return validated(() -> repository.saveRevision(versionedRevision(documentId, revisionNumber, expectedVersion).edit(content)));
    }

    public KnowledgeRevision approve(long documentId, int revisionNumber, Long expectedVersion, long actorId, Instant now) {
        return validated(() -> repository.saveRevision(versionedRevision(documentId, revisionNumber, expectedVersion).approve(actorId, now)));
    }

    public KnowledgeRevision publish(long documentId, int revisionNumber, Instant now) {
        return validated(() -> {
            Objects.requireNonNull(now, "now");
            KnowledgeDocument document = document(documentId);
            KnowledgeRevision target = revision(documentId, revisionNumber);
            if (Objects.equals(document.publishedRevision(), revisionNumber) && target.status() == KnowledgeStatus.PUBLISHED) {
                return target;
            }
            KnowledgeDocument changed = document.publish(revisionNumber);
            KnowledgeRevision published = target.publish(now);
            // 루트를 먼저 flush하고 리비전 변경을 같은 트랜잭션에 저장한다.
            repository.saveDocument(changed);
            if (document.publishedRevision() != null) {
                repository.saveRevision(revision(documentId, document.publishedRevision()).archive());
            }
            return repository.saveRevision(published);
        });
    }

    public KnowledgeRevision archive(long documentId, int revisionNumber) {
        return validated(() -> {
            KnowledgeDocument document = document(documentId);
            KnowledgeRevision target = revision(documentId, revisionNumber);
            if (Objects.equals(document.publishedRevision(), revisionNumber)) {
                repository.saveDocument(document.unpublish());
            }
            return target.status() == KnowledgeStatus.ARCHIVED ? target : repository.saveRevision(target.archive());
        });
    }

    @Transactional(readOnly = true)
    public List<KnowledgeRevision> published(KnowledgeLocale locale, Instant now, int limit) {
        return validated(() -> {
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit");
            }
            return repository.findPublished(Objects.requireNonNull(locale), Objects.requireNonNull(now), limit);
        });
    }

    private KnowledgeDocument document(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("documentId");
        }
        return repository.findDocument(id).orElseThrow(() -> new KnowledgeException(KnowledgeErrorCode.NOT_FOUND));
    }

    private KnowledgeRevision revision(long documentId, int number) {
        if (documentId <= 0 || number < 1) {
            throw new IllegalArgumentException("revisionNumber");
        }
        return repository.findRevision(documentId, number).orElseThrow(() -> new KnowledgeException(KnowledgeErrorCode.NOT_FOUND));
    }

    private KnowledgeRevision versionedRevision(long documentId, int number, Long expectedVersion) {
        KnowledgeRevision revision = revision(documentId, number);
        if (expectedVersion == null || !Objects.equals(expectedVersion, revision.version())) {
            throw new KnowledgeException(KnowledgeErrorCode.CONCURRENTLY_CHANGED);
        }
        return revision;
    }

    private <T> T validated(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_INPUT);
        } catch (IllegalStateException exception) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_TRANSITION);
        }
    }
}
