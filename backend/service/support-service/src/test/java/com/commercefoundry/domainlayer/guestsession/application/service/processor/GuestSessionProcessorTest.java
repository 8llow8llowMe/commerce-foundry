package com.commercefoundry.domainlayer.guestsession.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionCryptoPort;
import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionStorePort;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuestSessionProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00.123456789Z");
    private static final String TOKEN = "A".repeat(43);
    private static final String HASH = "B".repeat(42) + "A";
    private final GuestSessionStorePort store = mock(GuestSessionStorePort.class);
    private final GuestSessionCryptoPort crypto = mock(GuestSessionCryptoPort.class);
    private final Clock clock = mock(Clock.class);
    private final GuestSessionProcessor processor = new GuestSessionProcessor(store, crypto, new SnowflakeIdGenerator(1, 1), clock);

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(crypto.generate()).thenReturn(TOKEN);
        when(crypto.fingerprint(TOKEN)).thenReturn(HASH);
        when(store.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issuanceReadsClockOnceAndPersistsOnlyFingerprintWithFixedLifetime() {
        var issued = processor.issue();
        assertThat(issued.token()).isEqualTo(TOKEN);
        assertThat(issued.expiresAt()).isEqualTo(Instant.parse("2026-11-10T00:00:00.123456Z"));
        verify(clock).instant();
        verify(store).saveAndFlush(argThat(session -> session.tokenHash().equals(HASH) && session.version() == null));
    }

    @Test
    void storageFailureAndGeneratorContractViolationCannotReturnToken() {
        when(store.saveAndFlush(any())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(processor::issue).isInstanceOf(IllegalStateException.class);
        reset(store);
        when(crypto.generate()).thenReturn("invalid");
        assertThatThrownBy(processor::issue).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }

    @Test
    void allUnavailableCasesUseOneFixedErrorAndInvalidFormatsNeverHashOrQuery() {
        for (String token : new String[] {null, "", "bad", "A".repeat(42) + "B"}) {
            unavailable(() -> processor.resolve(token));
        }
        verifyNoInteractions(crypto, store);
        when(store.findByTokenHash(HASH)).thenReturn(Optional.empty());
        unavailable(() -> processor.resolve(TOKEN));
        for (GuestSession state : new GuestSession[] {
            new GuestSession(1, HASH, NOW.minusSeconds(1), null, 0L),
            new GuestSession(1, HASH, NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS), null, 0L),
            new GuestSession(1, HASH, NOW.plus(Duration.ofDays(1)), NOW.minusSeconds(1), 0L)}) {
            when(store.findByTokenHash(HASH)).thenReturn(Optional.of(state));
            unavailable(() -> processor.resolve(TOKEN));
        }
    }

    @Test
    void resolutionDoesNotSlideAndRevokeNoOpsDoNotWrite() {
        var active = new GuestSession(1, HASH, NOW.plusSeconds(60), null, 0L);
        when(store.findByTokenHash(HASH)).thenReturn(Optional.of(active));
        assertThat(processor.resolve(TOKEN).expiresAt()).isEqualTo(active.expiresAt());
        verify(store, never()).saveAndFlush(any());
        when(store.findById(1)).thenReturn(Optional.of(active.revoke(NOW.minusSeconds(1))));
        processor.revoke(1);
        when(store.findById(1)).thenReturn(Optional.of(new GuestSession(1, HASH, NOW.minusSeconds(1), null, 0L)));
        processor.revoke(1);
        verify(store, never()).saveAndFlush(any());
        when(store.findById(1)).thenReturn(Optional.of(active));
        processor.revoke(1);
        verify(store).saveAndFlush(argThat(session -> session.revokedAt().equals(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS))));
        when(store.findById(2)).thenReturn(Optional.empty());
        unavailable(() -> processor.revoke(2));
    }

    @Test
    void lockedResolutionAuthenticatesOnlyAfterTheStoreHasAcquiredAndRefreshedTheRow() {
        var active = new GuestSession(1, HASH, NOW.plusSeconds(60), null, 0L);
        when(store.findByTokenHashForUpdate(HASH)).thenReturn(Optional.of(active));
        var identity = processor.resolveForUpdate(TOKEN);
        assertThat(identity.id()).isEqualTo(1);
        assertThat(identity.expiresAt()).isEqualTo(active.expiresAt());
        var order = inOrder(crypto, store, clock);
        order.verify(crypto).fingerprint(TOKEN);
        order.verify(store).findByTokenHashForUpdate(HASH);
        order.verify(clock).instant();
        verify(store, never()).findByTokenHash(any());
        verify(store, never()).saveAndFlush(any());
    }

    @Test
    void lockedResolutionRejectsMalformedWithoutClockOrStoreAndChecksNormalizedExpiry() {
        unavailable(() -> processor.resolveForUpdate("malformed"));
        verifyNoInteractions(crypto, store, clock);
        var boundary = new GuestSession(1, HASH, NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS), null, 0L);
        when(store.findByTokenHashForUpdate(HASH)).thenReturn(Optional.of(boundary));
        unavailable(() -> processor.resolveForUpdate(TOKEN));
    }

    private void unavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(GuestSessionException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(GuestSessionErrorCode.UNAVAILABLE);
            assertThat(exception.getMessage()).isEqualTo("Guest session is unavailable.");
        });
    }
}
