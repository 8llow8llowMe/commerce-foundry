package com.commercefoundry.domainlayer.guestsession.application.info;

import java.time.Instant;

public record GuestSessionIssuedInfo(long id, String token, Instant expiresAt) {
    @Override
    public String toString() {
        return "GuestSessionIssuedInfo[id=" + id + ", token=REDACTED]";
    }
}
