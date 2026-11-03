package com.commercefoundry.domainlayer.guestsession.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;

import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository.GuestSessionRepository;
import com.commercefoundry.domainlayer.guestsession.application.mapper.GuestSessionMapperImpl;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.entity.GuestSessionEntity;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import java.util.Optional;
import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class GuestSessionStoreAdapterTest {
    @Test
    void unrelatedOrUnclassifiedIntegrityErrorsRetainTheirOriginalMeaning() {
        var repository = mock(GuestSessionRepository.class);
        var adapter = new GuestSessionStoreAdapter(repository, new GuestSessionMapperImpl(), mock(jakarta.persistence.EntityManager.class));
        var failure = new DataIntegrityViolationException("unrelated constraint");
        when(repository.saveAndFlush(any())).thenThrow(failure);
        var session = new GuestSession(1, "A".repeat(43), Instant.parse("2026-11-10T00:00:00Z"), null, null);
        assertThatThrownBy(() -> adapter.saveAndFlush(session)).isSameAs(failure);
    }
    @Test
    void lookupAndLockRefreshFailuresAreSanitized() {
        var repository = mock(GuestSessionRepository.class);
        var manager = mock(EntityManager.class);
        var adapter = new GuestSessionStoreAdapter(repository, new GuestSessionMapperImpl(), manager);
        String fingerprint = "A".repeat(43);
        when(repository.findIdByTokenHash(fingerprint)).thenThrow(new org.springframework.dao.CannotAcquireLockException("secret SQL"));
        busy(() -> adapter.findByTokenHashForUpdate(fingerprint));
        var entity = GuestSessionEntity.builder().id(1L).tokenHash(fingerprint).expiresAt(Instant.parse("2026-11-10T00:00:00Z")).build();
        doReturn(Optional.of(1L)).when(repository).findIdByTokenHash(fingerprint);
        when(manager.getReference(GuestSessionEntity.class, 1L)).thenReturn(entity);
        doThrow(new LockTimeoutException("secret SQL")).when(manager).refresh(entity, LockModeType.PESSIMISTIC_WRITE,
            Map.of("jakarta.persistence.lock.timeout", 0));
        busy(() -> adapter.findByTokenHashForUpdate(fingerprint));
    }

    private void busy(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GuestSessionException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(GuestSessionErrorCode.LOCK_UNAVAILABLE);
            assertThat(failure.getMessage()).isEqualTo("Guest session is busy.");
            assertThat(failure.getCause()).isNull();
        });
    }
}
