package com.commercefoundry.domainlayer.guestsession.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Authenticated identity only; valid while the caller retains the guest row lock. */
public record GuestSessionIdentity(long id, Instant expiresAt) {
    public GuestSessionIdentity {
        if (id <= 0) {
            throw new IllegalArgumentException("Invalid guest identity");
        }
        Objects.requireNonNull(expiresAt);
    }
}
