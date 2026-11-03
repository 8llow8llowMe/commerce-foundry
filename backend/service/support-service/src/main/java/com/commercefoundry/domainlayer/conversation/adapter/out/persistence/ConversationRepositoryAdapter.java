package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.ConversationRepository;
import com.commercefoundry.domainlayer.conversation.application.mapper.ConversationMapper;
import com.commercefoundry.domainlayer.conversation.application.port.out.ConversationRepositoryPort;
import com.commercefoundry.domainlayer.conversation.domain.model.Conversation;
import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.ConversationEntity;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConversationRepositoryAdapter implements ConversationRepositoryPort {
    private final ConversationRepository repository;
    private final ConversationMapper mapper;
    private final EntityManager entityManager;

    @Override
    public Optional<Long> findLastSeqByIdAndMemberId(long id, long memberId) {
        return repository.findLastSeqByIdAndMemberId(id, memberId);
    }

    @Override
    public Optional<Long> findLastSeqByIdAndGuestSessionId(long id, long guestSessionId) {
        return repository.findLastSeqByIdAndGuestSessionId(id, guestSessionId);
    }

    @Override
    public Conversation saveAndFlush(Conversation conversation) {
        try {
            return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(conversation)));
        } catch (org.springframework.dao.OptimisticLockingFailureException | jakarta.persistence.OptimisticLockException exception) {
            throw new ConversationException(ConversationErrorCode.CONCURRENTLY_CHANGED);
        }
    }

    @Override
    public Optional<Conversation> findByIdAndMemberId(long id, long memberId) {
        return repository.findByIdAndMemberId(id, memberId).map(mapper::toDomain);
    }

    @Override
    public Optional<Conversation> findByIdAndGuestSessionId(long id, long guestSessionId) {
        return repository.findByIdAndGuestSessionId(id, guestSessionId).map(mapper::toDomain);
    }

    @Override
    public Optional<Conversation> findByIdAndMemberIdForUpdate(long id, long memberId) {
        return locked(id, memberId, true);
    }

    @Override
    public Optional<Conversation> findByIdAndGuestSessionIdForUpdate(long id, long guestSessionId) {
        return locked(id, guestSessionId, false);
    }

    private Optional<Conversation> locked(long id, long owner, boolean member) {
        try {
            Optional<Long> found = member ? repository.findIdByIdAndMemberId(id, owner) : repository.findIdByIdAndGuestSessionId(id, owner);
            return found.flatMap(key -> {
                // ID projection avoids comparing a stale managed version before refresh acquires the lock.
                ConversationEntity entity = entityManager.getReference(ConversationEntity.class, key);
                entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0));
                Long actual = member ? entity.getMemberId() : entity.getGuestSessionId();
                return Objects.equals(actual, owner) ? Optional.of(mapper.toDomain(entity)) : Optional.empty();
            });
        } catch (EntityNotFoundException exception) {
            return Optional.empty();
        } catch (org.springframework.dao.PessimisticLockingFailureException | LockTimeoutException | PessimisticLockException
                 | jakarta.persistence.QueryTimeoutException | org.springframework.dao.QueryTimeoutException exception) {
            throw new ConversationException(ConversationErrorCode.LOCK_UNAVAILABLE);
        } catch (org.springframework.dao.OptimisticLockingFailureException | jakarta.persistence.OptimisticLockException exception) {
            throw new ConversationException(ConversationErrorCode.CONCURRENTLY_CHANGED);
        }
    }
}
