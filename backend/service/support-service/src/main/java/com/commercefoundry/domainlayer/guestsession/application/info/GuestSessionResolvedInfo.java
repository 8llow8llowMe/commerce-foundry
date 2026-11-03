package com.commercefoundry.domainlayer.guestsession.application.info;

import java.time.Instant;

public record GuestSessionResolvedInfo(long id, Instant expiresAt) {
}
