package com.commercefoundry.domainlayer.guestsession.adapter.out.crypto;

import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionCryptoPort;
import com.commercefoundry.security.issuer.OpaqueTokenGenerator;
import org.springframework.stereotype.Component;

@Component
public class GuestSessionCryptoAdapter implements GuestSessionCryptoPort {
    // Pure generator: deliberately independent of JWT issuer configuration and private keys.
    private final OpaqueTokenGenerator generator = new OpaqueTokenGenerator();

    @Override
    public String generate() {
        return generator.generate();
    }

    @Override
    public String fingerprint(String token) {
        return generator.fingerprint(token);
    }
}
