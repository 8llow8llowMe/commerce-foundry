package com.commercefoundry.domainlayer.conversation.application.exception;

import lombok.Getter;

@Getter
public class ConversationException extends RuntimeException {
    private final ConversationErrorCode errorCode;

    public ConversationException(ConversationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ConversationException(ConversationErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public ConversationException(ConversationErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
