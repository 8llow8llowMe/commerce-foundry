package com.commercefoundry.domainlayer.guestsession.application.service.processor;

import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionIssuedInfo;
import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionResolvedInfo;
import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionCryptoPort;
import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionStorePort;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSessionToken;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class GuestSessionProcessor {
    // Fixed credential protocol, not a store-specific policy. No sliding renewal.
    private static final Duration LIFETIME = Duration.ofDays(7);
    private final GuestSessionStorePort store;
    private final GuestSessionCryptoPort crypto;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    public GuestSessionIssuedInfo issue() {
        Instant now = now();
        String token = crypto.generate();
        if (!GuestSessionToken.isCanonical(token)) {
            throw new IllegalStateException("Guest credential generator violated its format contract");
        }
        GuestSession saved = store.saveAndFlush(new GuestSession(ids.generateId(), crypto.fingerprint(token), now.plus(LIFETIME), null, null));
        return new GuestSessionIssuedInfo(saved.id(), token, saved.expiresAt());
    }

    /** Valid only at this authentication instant; a read version does not fence concurrent revoke. */
    @Transactional(readOnly = true)
    public GuestSessionResolvedInfo resolve(String token) {
        Instant now = now();
        if (!GuestSessionToken.isCanonical(token)) {
            throw unavailable();
        }
        GuestSession session = store.findByTokenHash(crypto.fingerprint(token)).orElseThrow(GuestSessionProcessor::unavailable);
        if (!session.isActive(now)) {
            throw unavailable();
        }
        return new GuestSessionResolvedInfo(session.id(), session.expiresAt());
    }

    /** Privileged internal operation; public authorization belongs to a future entry point. */
    public void revoke(long sessionId) {
        Instant now = now();
        GuestSession session = store.findById(sessionId).orElseThrow(GuestSessionProcessor::unavailable);
        GuestSession changed = session.revoke(now);
        if (changed != session) {
            store.saveAndFlush(changed);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static GuestSessionException unavailable() {
        return new GuestSessionException(GuestSessionErrorCode.UNAVAILABLE);
    }
}
