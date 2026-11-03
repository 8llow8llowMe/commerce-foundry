package com.commercefoundry.domainlayer.conversation.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ConversationErrorCode {
    NOT_FOUND("SUPPORT_010", "Conversation is unavailable.", HttpStatus.NOT_FOUND),
    INVALID_OWNER("SUPPORT_110", "Conversation owner must be a positive identifier.", HttpStatus.BAD_REQUEST),
    INVALID_LOCALE("SUPPORT_111", "Conversation locale is required.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
