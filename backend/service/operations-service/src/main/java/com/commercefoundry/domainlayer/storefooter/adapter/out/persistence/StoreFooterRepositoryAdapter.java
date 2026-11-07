package com.commercefoundry.domainlayer.storefooter.adapter.out.persistence;

import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity.StoreFooterEntity;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity.StoreFooterRevisionEntity;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity.StoreFooterLinkEntity;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterRepository;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterRevisionRepository;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterLinkRepository;
import com.commercefoundry.domainlayer.storefooter.application.exception.StoreFooterErrorCode;
import com.commercefoundry.domainlayer.storefooter.application.exception.StoreFooterException;
import com.commercefoundry.domainlayer.storefooter.application.port.out.StoreFooterRepositoryPort;
import com.commercefoundry.domainlayer.storefooter.application.port.out.query.StoreFooterQueryResults;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooter;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterUrlPolicy;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@RequiredArgsConstructor
public class StoreFooterRepositoryAdapter implements StoreFooterRepositoryPort {
    private final StoreFooterRepository footers;
    private final StoreFooterRevisionRepository revisions;
    private final StoreFooterLinkRepository links;
    private final EntityManager entityManager;
    private final StoreFooterUrlPolicy urls;
    private final PlatformTransactionManager transactions;

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreFooterQueryResults.Detail> detail() {
        return footers.findById(1L).map(entity -> detail(entity.toDomain()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreFooterRevision> published() {
        return footers.findById(1L).map(StoreFooterEntity::toDomain)
            .filter(footer -> footer.publishedRevision() != null).map(footer -> revision(footer.publishedRevision()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoreFooterRevision> revisions(Pageable pageable) {
        root();
        var page = revisions.findAllByFooterId(1L, pageable);
        if (page.isEmpty()) {
            return page.map(entity -> entity.toDomain(List.of()));
        }
        var ids = page.getContent().stream().map(StoreFooterRevisionEntity::getId).toList();
        Map<Long, List<StoreFooterContent.Link>> grouped = links.findAllByRevisionIdInOrderByDisplayOrderAsc(ids).stream()
            .collect(Collectors.groupingBy(StoreFooterLinkEntity::getRevisionId, Collectors.mapping(StoreFooterLinkEntity::toDomain, Collectors.toList())));
        return page.map(entity -> entity.toDomain(grouped.getOrDefault(entity.getId(), List.of())));
    }

    @Override
    public StoreFooterQueryResults.Detail saveDraft(Long expectedVersion, StoreFooterContent content, long revisionId, List<Long> linkIds,
                                                         long actorMemberId, Instant now) {
        return commit(expectedVersion == null, () -> {
            var existing = footers.findById(1L);
            var footer = existing.map(StoreFooterEntity::toDomain).orElseGet(() -> new StoreFooter(0, null, null));
            var next = footer.append(expectedVersion);
            var entity = existing.orElseGet(() -> StoreFooterEntity.create(next));
            entity.apply(next);
            if (existing.isEmpty()) {
                // Persist the assigned singleton key: merge must never overwrite a competing first insert.
                entityManager.persist(entity);
            }
            entityManager.flush();
            var revision = new StoreFooterRevision(revisionId, next.latestRevision(), content, actorMemberId, now, null, null);
            entityManager.persist(StoreFooterRevisionEntity.create(revision));
            for (int index = 0; index < content.links().size(); index++) {
                entityManager.persist(StoreFooterLinkEntity.create(linkIds.get(index), revision.id(), content.links().get(index)));
            }
            entityManager.flush();
            return new StoreFooterQueryResults.Detail(entity.toDomain(), revision);
        });
    }

    @Override
    public StoreFooterQueryResults.Detail publish(Long expectedVersion, int revisionNumber, long actorMemberId, Instant now) {
        return commit(false, () -> {
            var entity = root();
            var footer = entity.toDomain();
            var next = footer.publish(expectedVersion, revisionNumber);
            if (next == footer) {
                return detail(footer);
            }
            var revisionEntity = revisionEntity(revisionNumber);
            var revision = revision(revisionEntity);
            // Deployment allowlist changes also apply to an existing draft before its first publication.
            StoreFooterValidator.validate(revision.content(), urls);
            var published = revision.publish(actorMemberId, now);
            entity.apply(next);
            revisionEntity.applyPublication(published);
            entityManager.flush();
            return new StoreFooterQueryResults.Detail(entity.toDomain(), published);
        });
    }

    /** End the physical write transaction before returning a result or classifying a commit failure. */
    private StoreFooterQueryResults.Detail commit(boolean creating, Supplier<StoreFooterQueryResults.Detail> work) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return transaction.execute(status -> work.get());
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new StoreFooterException(StoreFooterErrorCode.CONFLICT, exception);
        } catch (DataIntegrityViolationException | PersistenceException exception) {
            if (StoreFooterConflictSupport.knownIntegrityConflict(exception, creating)) {
                throw new StoreFooterException(StoreFooterErrorCode.CONFLICT, exception);
            }
            throw exception;
        } catch (TransactionException exception) {
            if (StoreFooterConflictSupport.knownIntegrityConflict(exception, creating)) {
                throw new StoreFooterException(StoreFooterErrorCode.CONFLICT, exception);
            }
            throw exception;
        }
    }

    private StoreFooterQueryResults.Detail detail(StoreFooter footer) {
        return new StoreFooterQueryResults.Detail(footer, revision(footer.latestRevision()));
    }

    private StoreFooterEntity root() {
        return footers.findById(1L).orElseThrow(() -> new StoreFooterException(StoreFooterErrorCode.NOT_FOUND));
    }

    private StoreFooterRevision revision(int revisionNumber) {
        return revision(revisionEntity(revisionNumber));
    }

    private StoreFooterRevisionEntity revisionEntity(int revisionNumber) {
        return revisions.findByFooterIdAndRevisionNumber(1L, revisionNumber)
            .orElseThrow(() -> new StoreFooterException(StoreFooterErrorCode.NOT_FOUND));
    }

    private StoreFooterRevision revision(StoreFooterRevisionEntity entity) {
        return entity.toDomain(links.findAllByRevisionIdInOrderByDisplayOrderAsc(List.of(entity.getId())).stream().map(StoreFooterLinkEntity::toDomain).toList());
    }
}
