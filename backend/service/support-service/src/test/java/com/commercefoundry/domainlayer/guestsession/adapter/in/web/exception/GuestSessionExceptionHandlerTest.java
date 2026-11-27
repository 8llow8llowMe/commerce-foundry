package com.commercefoundry.domainlayer.guestsession.adapter.in.web.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GuestSessionExceptionHandlerTest {
    private static final String TOKEN = "A".repeat(43);

    @Test
    void fingerprintCollisionIs409WithFixedMessageOnly() {
        var exception = new GuestSessionException(GuestSessionErrorCode.TOKEN_COLLISION, new IllegalStateException("uk_support_guest_session_token_hash " + TOKEN));

        var response = new GuestSessionExceptionHandler().guest(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var header = response.getBody().dataHeader();
        assertThat(header.resultCode()).isEqualTo("GUEST_SESSION_003");
        assertThat(header.resultMessage()).isEqualTo(GuestSessionErrorCode.TOKEN_COLLISION.getMessage()).doesNotContain(TOKEN);
        assertThat(response.getBody().dataBody()).isNull();
    }
}
