package com.commercefoundry.domainlayer.guestsession.application.port.out;

public interface GuestSessionCryptoPort {
    String generate();
    String fingerprint(String token);
}
