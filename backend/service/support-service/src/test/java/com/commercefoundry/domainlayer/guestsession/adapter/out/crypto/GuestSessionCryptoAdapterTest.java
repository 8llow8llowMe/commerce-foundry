package com.commercefoundry.domainlayer.guestsession.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSessionToken;
import com.commercefoundry.security.issuer.OpaqueTokenGenerator;
import org.junit.jupiter.api.Test;

class GuestSessionCryptoAdapterTest {
    @Test
    void usesCoreOpaqueCredentialAndFingerprintContract() {
        var adapter = new GuestSessionCryptoAdapter();
        String token = adapter.generate();
        assertThat(GuestSessionToken.isCanonical(token)).isTrue();
        assertThat(adapter.generate()).isNotEqualTo(token);
        assertThat(adapter.fingerprint(token)).isEqualTo(new OpaqueTokenGenerator().fingerprint(token)).isNotEqualTo(token);
        assertThat(GuestSessionToken.isCanonical(adapter.fingerprint(token))).isTrue();
    }
}
