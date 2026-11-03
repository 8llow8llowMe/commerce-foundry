package com.commercefoundry.domainlayer.conversation.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ConversationErrorCode {
    NOT_FOUND("SUPPORT_010", "Conversation is unavailable.", HttpStatus.NOT_FOUND),
    MESSAGE_STATE_UNAVAILABLE("SUPPORT_011", "Conversation does not accept new messages.", HttpStatus.CONFLICT),
    LOCK_UNAVAILABLE("SUPPORT_012", "Conversation is busy. Retry later.", HttpStatus.CONFLICT),
    CONCURRENTLY_CHANGED("SUPPORT_013", "Conversation was concurrently changed.", HttpStatus.CONFLICT),
    MESSAGE_CONFLICT("SUPPORT_014", "Message request conflicts with an existing message.", HttpStatus.CONFLICT),
    SEQUENCE_EXHAUSTED("SUPPORT_015", "Conversation sequence is exhausted.", HttpStatus.CONFLICT),
    INVALID_OWNER("SUPPORT_110", "Conversation owner must be a positive identifier.", HttpStatus.BAD_REQUEST),
    INVALID_LOCALE("SUPPORT_111", "Conversation locale is required.", HttpStatus.BAD_REQUEST),
    INVALID_MESSAGE_KEY("SUPPORT_112", "Message key must be a canonical lowercase UUID.", HttpStatus.BAD_REQUEST),
    INVALID_MESSAGE_CONTENT("SUPPORT_113", "Message content must contain 1 to 2000 UTF-16 units.", HttpStatus.BAD_REQUEST),
    INVALID_MESSAGE_CURSOR("SUPPORT_114", "Message cursor must be between zero and the observed last sequence.", HttpStatus.BAD_REQUEST),
    INVALID_MESSAGE_PAGE_SIZE("SUPPORT_115", "Message page size must be between 1 and 100.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
