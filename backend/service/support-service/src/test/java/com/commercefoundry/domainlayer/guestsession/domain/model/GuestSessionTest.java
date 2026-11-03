package com.commercefoundry.domainlayer.guestsession.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionIssuedInfo;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GuestSessionTest {
    private static final Instant END = Instant.parse("2026-11-10T00:00:00Z");
    private static final String HASH = "A".repeat(43);

    @Test
    void expiryBoundaryAndRevocationAreAbsolute() {
        var session = new GuestSession(1, HASH, END, null, 0L);
        assertThat(session.isActive(END.minusNanos(1))).isTrue();
        assertThat(session.isActive(END)).isFalse();
        assertThat(session.isActive(END.plusNanos(1))).isFalse();
        assertThat(session.revoke(END)).isSameAs(session);
        var revoked = session.revoke(END.minusSeconds(1));
        assertThat(revoked.revokedAt()).isEqualTo(END.minusSeconds(1));
        assertThat(revoked.isActive(END.minusMillis(500))).isFalse();
        assertThat(revoked.revoke(END.minusMillis(500))).isSameAs(revoked);
    }

    @Test
    void canonicalEncodingAndStateValidation() {
        assertThat(GuestSessionToken.isCanonical(HASH)).isTrue();
        for (String invalid : new String[] {"", "A".repeat(42), HASH + "=", "A".repeat(42) + "B", "!".repeat(43)}) {
            assertThat(GuestSessionToken.isCanonical(invalid)).isFalse();
        }
        assertThat(GuestSessionToken.isCanonical(null)).isFalse();
        assertThatThrownBy(() -> new GuestSession(0, HASH, END, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuestSession(1, "invalid", END, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stringRepresentationsRedactCredentials() {
        assertThat(new GuestSession(1, HASH, END, null, null).toString()).doesNotContain(HASH);
        assertThat(new GuestSessionIssuedInfo(1, HASH, END).toString()).doesNotContain(HASH);
    }
}
