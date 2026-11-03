package com.commercefoundry.domainlayer.guestsession.adapter.out.persistence;

import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository.GuestSessionRepository;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.mapper.GuestSessionMapper;
import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionStorePort;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class GuestSessionStoreAdapter implements GuestSessionStorePort {
    private final GuestSessionRepository repository;
    private final GuestSessionMapper mapper;

    @Override
    public Optional<GuestSession> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(mapper::toDomain);
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
