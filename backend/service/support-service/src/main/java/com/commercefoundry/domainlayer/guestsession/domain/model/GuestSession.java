package com.commercefoundry.domainlayer.guestsession.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Immutable credential state. Never include the fingerprint in diagnostic text. */
public record GuestSession(long id, String tokenHash, Instant expiresAt, Instant revokedAt, Long version) {
    public GuestSession {
        if (id <= 0 || !GuestSessionToken.isCanonical(tokenHash)) {
            throw new IllegalArgumentException("Invalid guest session state");
        }
        Objects.requireNonNull(expiresAt);
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public GuestSession revoke(Instant now) {
        return isActive(now) ? new GuestSession(id, tokenHash, expiresAt, now, version) : this;
    }

    @Override
    public String toString() {
        return "GuestSession[id=" + id + ", credential=REDACTED]";
    }
}
