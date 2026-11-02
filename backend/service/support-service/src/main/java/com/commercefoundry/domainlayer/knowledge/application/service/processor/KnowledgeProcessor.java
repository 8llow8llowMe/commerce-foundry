package com.commercefoundry.domainlayer.knowledge.application.service.processor;

import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeContentCommand;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeSearchCommand;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeErrorCode;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeException;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeAdminInfo;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeSearchInfo;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeSummaryInfo;
import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeSearchCriteria;
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
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 지식 게시 정책의 불변식과 버전 계약을 적용한다. */
@Component
@RequiredArgsConstructor
@Transactional
public class KnowledgeProcessor {

    private final KnowledgeRepositoryPort repository;
    private final SnowflakeIdGenerator idGenerator;

    // One SELECT joins the current pointer and revision in the database statement snapshot.
    @Transactional(readOnly = true)
    public List<KnowledgeSearchInfo> search(KnowledgeSearchCommand command, Instant cutoff) {
        var criteria = KnowledgeSearchCriteria.from(command);
        return repository.search(criteria, Objects.requireNonNull(cutoff)).stream().map(row ->
            new KnowledgeSearchInfo(
                row.documentId(), row.revisionId(), row.revisionNumber(), row.title(), row.body(), row.category(), row.type(), row.locale(),
                row.score(), row.validFrom(), row.validTo())).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<KnowledgeSummaryInfo> listAdmin(KnowledgeType type, KnowledgeLocale locale, int page, int size) {
        return validated(() -> {
            if (page < 0 || size < 1 || size > 100) {
                throw new IllegalArgumentException("page");
            }
            return repository.findAdminSummaries(type, locale, page, size).map(row -> new KnowledgeSummaryInfo(
                row.documentId(), row.type(), row.locale(), row.latestRevision(), row.publishedRevision(), row.documentVersion(), row.revisionVersion(),
                row.title(), row.category(), row.status(), row.validFrom(), row.validTo(), row.approvedBy(), row.approvedAt(), row.publishedAt()));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeAdminInfo getAdmin(long documentId) {
        return validated(() -> {
            KnowledgeDocument document = document(documentId);
            return new KnowledgeAdminInfo(document, revision(documentId, document.latestRevision()));
        });
    }

    public KnowledgeAdminInfo createAdmin(KnowledgeType type, KnowledgeLocale locale, KnowledgeContentCommand command) {
        return validated(() -> {
            KnowledgeRevision created = create(type, locale, content(command));
            return new KnowledgeAdminInfo(document(created.documentId()), created);
        });
    }

    public KnowledgeAdminInfo createDraftAdmin(long documentId, Long expectedDocumentVersion, KnowledgeContentCommand command) {
        return validated(() -> {
            KnowledgeDocument document = document(documentId);
            requireVersion(expectedDocumentVersion, document.version());
            KnowledgeDocument next = document.nextDraft();
            KnowledgeRevision draft = KnowledgeRevision.draft(idGenerator.generateId(), documentId, next.latestRevision(), content(command));
            KnowledgeDocument saved = repository.saveDocument(next);
            return new KnowledgeAdminInfo(saved, repository.saveRevision(draft));
        });
    }

    public KnowledgeAdminInfo editAdmin(long documentId, int number, Long expectedVersion, KnowledgeContentCommand command) {
        return validated(() -> {
            KnowledgeDocument document = document(documentId);
            KnowledgeRevision target = versionedRevision(documentId, number, expectedVersion);
            requireLatest(document, number);
            KnowledgeRevision changed = target.edit(content(command));
            return new KnowledgeAdminInfo(document, repository.saveRevision(changed));
        });
    }

    public KnowledgeAdminInfo approveAdmin(long documentId, int number, Long expectedVersion, long actorId, Instant now) {
        return validated(() -> {
            KnowledgeDocument document = document(documentId);
            KnowledgeRevision target = versionedRevision(documentId, number, expectedVersion);
            requireLatest(document, number);
            KnowledgeRevision changed = target.approve(actorId, now);
            return new KnowledgeAdminInfo(document, repository.saveRevision(changed));
        });
    }

    public KnowledgeAdminInfo publishAdmin(long documentId, int number, Long expectedVersion, Long expectedDocumentVersion, Instant now) {
        return validated(() -> {
            KnowledgeDocument document = document(documentId);
            KnowledgeRevision target = revision(documentId, number);
            if (Objects.equals(document.publishedRevision(), number) && target.status() == KnowledgeStatus.PUBLISHED) {
                return new KnowledgeAdminInfo(document, target);
            }
            requireVersion(expectedDocumentVersion, document.version());
            requireVersion(expectedVersion, target.version());
            KnowledgeDocument changed = document.publish(number);
            KnowledgeRevision published = target.publish(now);
            KnowledgeDocument saved = repository.saveDocument(changed);
            if (document.publishedRevision() != null) {
                repository.saveRevision(revision(documentId, document.publishedRevision()).archive());
            }
            return new KnowledgeAdminInfo(saved, repository.saveRevision(published));
        });
    }

    public void archiveAdmin(long documentId, int number, Long expectedVersion, Long expectedDocumentVersion) {
        validated(() -> {
            KnowledgeDocument document = document(documentId);
            KnowledgeRevision target = revision(documentId, number);
            if (target.status() == KnowledgeStatus.ARCHIVED) {
                return target;
            }
            requireVersion(expectedDocumentVersion, document.version());
            requireVersion(expectedVersion, target.version());
            if (Objects.equals(document.publishedRevision(), number)) {
                repository.saveDocument(document.unpublish());
            }
            return repository.saveRevision(target.archive());
        });
    }

    private KnowledgeRevision.Content content(KnowledgeContentCommand command) {
        return new KnowledgeRevision.Content(command.title(), command.body(), command.category(), command.validFrom(), command.validTo());
    }

    private void requireLatest(KnowledgeDocument document, int number) {
        if (document.latestRevision() != number) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_TRANSITION);
        }
    }

    private void requireVersion(Long expected, Long actual) {
        if (expected == null || !Objects.equals(expected, actual)) {
            throw new KnowledgeException(KnowledgeErrorCode.CONCURRENTLY_CHANGED);
        }
    }

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
