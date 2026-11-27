package com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence;

import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity.StoreNavigationEntity;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity.StoreNavigationItemEntity;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity.StoreNavigationRevisionEntity;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationItemRepository;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationRepository;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationRevisionRepository;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationErrorCode;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationException;
import com.commercefoundry.domainlayer.storenavigation.application.port.out.StoreNavigationRepositoryPort;
import com.commercefoundry.domainlayer.storenavigation.application.port.out.query.StoreNavigationQueryResults;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigation;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRevision;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationUrlPolicy;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@RequiredArgsConstructor
public class StoreNavigationRepositoryAdapter implements StoreNavigationRepositoryPort {
    private final StoreNavigationRepository navigations;
    private final StoreNavigationRevisionRepository revisions;
    private final StoreNavigationItemRepository items;
    private final EntityManager entityManager;
    private final StoreNavigationUrlPolicy urls;
    private final PlatformTransactionManager transactions;

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreNavigationQueryResults.Detail> detail() {
        return navigations.findById(1L).map(entity -> detail(entity.toDomain()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreNavigationRevision> published() {
        return navigations.findById(1L).map(StoreNavigationEntity::toDomain)
            .filter(navigation -> navigation.publishedRevision() != null).map(navigation -> revision(navigation.publishedRevision()));
    }

    @Override
    public StoreNavigationQueryResults.Detail saveDraft(Long expectedVersion, StoreNavigationContent content, long revisionId,
                                                        long actorMemberId, Instant now) {
        return commit(expectedVersion == null, () -> {
            var existing = navigations.findById(1L);
            var navigation = existing.map(StoreNavigationEntity::toDomain).orElseGet(() -> new StoreNavigation(0, null, null));
            var next = navigation.append(expectedVersion);
            var entity = existing.orElseGet(() -> StoreNavigationEntity.create(next));
            entity.apply(next);
            if (existing.isEmpty()) {
                // 지정한 고정 키를 persist 한다. merge 는 경합한 최초 INSERT 를 덮어쓸 수 있다.
                entityManager.persist(entity);
            }
            entityManager.flush();
            var revision = new StoreNavigationRevision(revisionId, next.latestRevision(), content, actorMemberId, now, null, null);
            entityManager.persist(StoreNavigationRevisionEntity.create(revision));
            for (StoreNavigationContent.Item item : content.items()) {
                entityManager.persist(StoreNavigationItemEntity.create(revision.id(), item));
            }
            entityManager.flush();
            return new StoreNavigationQueryResults.Detail(entity.toDomain(), revision);
        });
    }

    @Override
    public StoreNavigationQueryResults.Detail publish(Long expectedVersion, int revisionNumber, long actorMemberId, Instant now) {
        return commit(false, () -> {
            var entity = root();
            var navigation = entity.toDomain();
            var next = navigation.publish(expectedVersion, revisionNumber);
            if (next == navigation) {
                // 현재 게시 리비전의 반복 게시: 버전·게시자·게시 시각을 바꾸지 않는다.
                return detail(navigation);
            }
            var revisionEntity = revisionEntity(revisionNumber);
            var revision = revision(revisionEntity);
            // 저장 뒤 배포 설정에서 허용 호스트가 빠졌을 수 있다. 새 게시는 지금 규칙으로 다시 검증한다.
            StoreNavigationValidator.validate(revision.content(), urls);
            var published = revision.publish(actorMemberId, now);
            entity.apply(next);
            revisionEntity.applyPublication(published);
            entityManager.flush();
            return new StoreNavigationQueryResults.Detail(entity.toDomain(), published);
        });
    }

    /** 결과를 돌려주거나 커밋 실패를 분류하기 전에 물리 쓰기 트랜잭션을 끝낸다(호출자 트랜잭션을 오염시키지 않는다). */
    private StoreNavigationQueryResults.Detail commit(boolean creating, Supplier<StoreNavigationQueryResults.Detail> work) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return transaction.execute(status -> work.get());
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new StoreNavigationException(StoreNavigationErrorCode.CONFLICT, exception);
        } catch (DataIntegrityViolationException | PersistenceException exception) {
            if (StoreNavigationConflictSupport.knownIntegrityConflict(exception, creating)) {
                throw new StoreNavigationException(StoreNavigationErrorCode.CONFLICT, exception);
            }
            throw exception;
        } catch (TransactionException exception) {
            if (StoreNavigationConflictSupport.knownIntegrityConflict(exception, creating)) {
                throw new StoreNavigationException(StoreNavigationErrorCode.CONFLICT, exception);
            }
            throw exception;
        }
    }

    private StoreNavigationQueryResults.Detail detail(StoreNavigation navigation) {
        return new StoreNavigationQueryResults.Detail(navigation, revision(navigation.latestRevision()));
    }

    private StoreNavigationEntity root() {
        return navigations.findById(1L).orElseThrow(() -> new StoreNavigationException(StoreNavigationErrorCode.NOT_FOUND));
    }

    private StoreNavigationRevision revision(int revisionNumber) {
        return revision(revisionEntity(revisionNumber));
    }

    private StoreNavigationRevisionEntity revisionEntity(int revisionNumber) {
        return revisions.findByNavigationIdAndRevisionNumber(1L, revisionNumber)
            .orElseThrow(() -> new StoreNavigationException(StoreNavigationErrorCode.NOT_FOUND));
    }

    private StoreNavigationRevision revision(StoreNavigationRevisionEntity entity) {
        var rows = items.findAllByRevisionIdIn(List.of(entity.getId())).stream().map(StoreNavigationItemEntity::toDomain).toList();
        // 행 순서는 보장되지 않는다. 저장 직후 결과(Processor 가 ordered() 로 넘긴 내용)와 같은 트리 전위 순서로 돌려준다.
        return entity.toDomain(new StoreNavigationContent(rows).ordered());
    }
}
