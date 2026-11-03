package com.commercefoundry.domainlayer.guestsession.application.port.in;

import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionIssuedInfo;
import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionResolvedInfo;

/** Internal Java contract only; no service HTTP endpoint is exposed. */
public interface GuestSessionInternalUseCase {
    GuestSessionIssuedInfo issue();
    GuestSessionResolvedInfo resolve(String token);
    void revoke(long sessionId);
}
