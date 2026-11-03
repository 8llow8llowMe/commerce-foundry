package com.commercefoundry.domainlayer.guestsession.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.guestsession.adapter.out.crypto.GuestSessionCryptoAdapter;
import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository.GuestSessionRepository;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.mapper.GuestSessionMapperImpl;
import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionStorePort;
import com.commercefoundry.domainlayer.guestsession.application.port.in.GuestSessionInternalUseCase;
import com.commercefoundry.domainlayer.guestsession.application.service.GuestSessionInternalFacade;
import com.commercefoundry.domainlayer.guestsession.application.service.processor.GuestSessionProcessor;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import com.commercefoundry.global.config.SupportServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Each operation commits independently; both database engines execute this contract. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SupportServiceFeaturesConfig.class, GuestSessionStoreAdapter.class, GuestSessionMapperImpl.class,
    GuestSessionCryptoAdapter.class, GuestSessionProcessor.class, GuestSessionInternalFacade.class, GuestSessionPersistenceContract.Config.class})
abstract class GuestSessionPersistenceContract {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00.123456Z");
    @Autowired GuestSessionProcessor processor;
    @Autowired GuestSessionInternalUseCase useCase;
    @Autowired GuestSessionStorePort store;
    @Autowired GuestSessionRepository repository;
    @Autowired GuestSessionCryptoAdapter crypto;
    @Autowired PlatformTransactionManager manager;
    @Autowired Clock clock;
    @MockitoSpyBean GuestSessionStoreAdapter adapter;
    private TransactionTemplate tx;

    @TestConfiguration
    static class Config {
        @Bean SnowflakeIdGenerator guestIds() { return new SnowflakeIdGenerator(1, 1); }
        @Bean Clock guestClock() { return mock(Clock.class); }
    }

    @BeforeEach
    void clean() {
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> repository.deleteAllInBatch());
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void resetMocks() {
        reset(adapter, clock);
    }

    Class<? extends RuntimeException> uniqueViolationType() {
        // H2 reports an index suffix, deliberately not classified as the PostgreSQL constraint.
        return DataIntegrityViolationException.class;
    }

    private GuestSession load(long id) {
        return tx.execute(status -> store.findById(id).orElseThrow());
    }

    @Test
    void storesOnlyHashAndKeepsSevenDayAbsoluteExpiryWithoutSliding() {
        var issued = processor.issue();
        GuestSession persisted = load(issued.id());
        assertThat(persisted.tokenHash()).isEqualTo(crypto.fingerprint(issued.token())).isNotEqualTo(issued.token());
        assertThat(persisted.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(repository.findById(issued.id()).orElseThrow().toString()).doesNotContain(issued.token(), persisted.tokenHash());
        var version = persisted.version();
        when(clock.instant()).thenReturn(issued.expiresAt().minusNanos(1000));
        assertThat(processor.resolve(issued.token()).expiresAt()).isEqualTo(issued.expiresAt());
        assertThat(load(issued.id()).version()).isEqualTo(version);
        when(clock.instant()).thenReturn(issued.expiresAt());
        unavailable(() -> processor.resolve(issued.token()));
        processor.revoke(issued.id());
        assertThat(load(issued.id())).isEqualTo(persisted);
        when(clock.instant()).thenReturn(issued.expiresAt().plusSeconds(1));
        unavailable(() -> processor.resolve(issued.token()));
    }

    @Test
    void revokePersistsOnceAndMissingCredentialsFailClosed() {
        var issued = processor.issue();
        processor.revoke(issued.id());
        GuestSession revoked = load(issued.id());
        assertThat(revoked.revokedAt()).isEqualTo(NOW);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        processor.revoke(issued.id());
        assertThat(load(issued.id())).isEqualTo(revoked);
        unavailable(() -> processor.resolve(issued.token()));
        unavailable(() -> processor.resolve(crypto.generate()));
        unavailable(() -> processor.revoke(Long.MAX_VALUE));
    }

    @Test
    void uniqueFingerprintConflictIsNotRetriedInFailedTransaction() {
        var issued = processor.issue();
        GuestSession current = load(issued.id());
        assertThatThrownBy(() -> tx.execute(status -> store.saveAndFlush(
            new GuestSession(current.id() + 1, current.tokenHash(), current.expiresAt(), null, null))))
            .isInstanceOf(uniqueViolationType()).satisfies(exception -> {
                if (exception instanceof GuestSessionException guestException) {
                    assertThat(guestException.getErrorCode()).isEqualTo(GuestSessionErrorCode.TOKEN_COLLISION);
                }
            });
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void staleVersionCannotRevokeAndStateRollbackIsAtomic() {
        var issued = processor.issue();
        GuestSession stale = load(issued.id());
        processor.revoke(issued.id());
        assertThatThrownBy(() -> tx.execute(status -> store.saveAndFlush(stale.revoke(NOW.plusSeconds(1)))))
            .isInstanceOfSatisfying(GuestSessionException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(GuestSessionErrorCode.CONCURRENTLY_CHANGED));
        var second = processor.issue();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            processor.revoke(second.id());
            throw new IllegalStateException("force rollback after flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(load(second.id()).revokedAt()).isNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            processor.issue();
            throw new IllegalStateException("force rollback after issue flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void facadeIssuanceCommitsIndependentlyOfCallerRollback() {
        var issued = tx.execute(status -> {
            var credential = useCase.issue();
            status.setRollbackOnly();
            return credential;
        });
        assertThat(issued).isNotNull();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(useCase.resolve(issued.token()).id()).isEqualTo(issued.id());
    }

    @Test
    void commitFailureDoesNotReturnIssuedCredentialAndRollsBackFlushedRow() {
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("force commit failure after flush");
                }
            });
            return saved;
        }).when(adapter).saveAndFlush(any());
        assertThatThrownBy(useCase::issue).isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void twoRealRevokeTransactionsHaveOneWinnerAndOneConflict() throws Exception {
        var issued = processor.issue();
        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            barrier.await(10, TimeUnit.SECONDS);
            return result;
        }).when(adapter).findById(anyLong());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> revokeOutcome(issued.id()));
            var second = executor.submit(() -> revokeOutcome(issued.id()));
            assertThat(java.util.Arrays.asList(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("SUCCESS", GuestSessionErrorCode.CONCURRENTLY_CHANGED.getCode());
        } finally {
            reset(adapter);
        }
        assertThat(load(issued.id()).version()).isEqualTo(1L);
        assertThat(load(issued.id()).revokedAt()).isEqualTo(NOW);
    }

    private String revokeOutcome(long id) {
        try {
            processor.revoke(id);
            return "SUCCESS";
        } catch (GuestSessionException exception) {
            return exception.getErrorCode().getCode();
        }
    }

    private void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GuestSessionException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(GuestSessionErrorCode.UNAVAILABLE);
            assertThat(exception.getMessage()).isEqualTo("Guest session is unavailable.");
        });
    }
}
