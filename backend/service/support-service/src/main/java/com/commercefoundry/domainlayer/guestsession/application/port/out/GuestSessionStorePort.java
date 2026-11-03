package com.commercefoundry.domainlayer.guestsession.application.port.out;

import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import java.util.Optional;

public interface GuestSessionStorePort {
    Optional<GuestSession> findByTokenHash(String tokenHash);
    Optional<GuestSession> findById(long id);
    GuestSession saveAndFlush(GuestSession session);
}
