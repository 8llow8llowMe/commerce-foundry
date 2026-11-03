package com.commercefoundry.domainlayer.guestsession.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GuestSessionErrorCode {
    UNAVAILABLE("GUEST_SESSION_001", "Guest session is unavailable.", HttpStatus.UNAUTHORIZED),
    CONCURRENTLY_CHANGED("GUEST_SESSION_002", "Guest session was concurrently changed.", HttpStatus.CONFLICT),
    TOKEN_COLLISION("GUEST_SESSION_003", "Guest session could not be issued.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
