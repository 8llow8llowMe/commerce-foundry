package com.commercefoundry.domainlayer.guestsession.adapter.out.persistence;

import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository.GuestSessionRepository;
import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.entity.GuestSessionEntity;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.mapper.GuestSessionMapper;
import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionStorePort;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Optional;
import java.util.Map;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class GuestSessionStoreAdapter implements GuestSessionStorePort {
    private final GuestSessionRepository repository;
    private final GuestSessionMapper mapper;
    private final EntityManager entityManager;

    @Override
    public Optional<GuestSession> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(mapper::toDomain);
    }

    @Override
    public Optional<GuestSession> findByTokenHashForUpdate(String tokenHash) {
        try {
            return repository.findIdByTokenHash(tokenHash).map(id -> {
                // An entity query can reject an already-managed stale version before refresh; project only the ID.
                GuestSessionEntity entity = entityManager.getReference(GuestSessionEntity.class, id);
                entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0));
                return mapper.toDomain(entity);
            });
        } catch (EntityNotFoundException exception) {
            return Optional.empty();
        } catch (PessimisticLockingFailureException | LockTimeoutException | PessimisticLockException
                 | jakarta.persistence.QueryTimeoutException | org.springframework.dao.QueryTimeoutException exception) {
            // Do not retain a cause containing SQL or credential material.
            throw new GuestSessionException(GuestSessionErrorCode.LOCK_UNAVAILABLE);
        }
    }

    @Override
    public Optional<GuestSession> findById(long id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public GuestSession saveAndFlush(GuestSession session) {
        try {
            return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(session)));
        } catch (OptimisticLockingFailureException exception) {
            throw new GuestSessionException(GuestSessionErrorCode.CONCURRENTLY_CHANGED, exception);
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, "uk_support_guest_session_token_hash")) {
                throw new GuestSessionException(GuestSessionErrorCode.TOKEN_COLLISION, exception);
            }
            throw exception;
        }
    }
}
