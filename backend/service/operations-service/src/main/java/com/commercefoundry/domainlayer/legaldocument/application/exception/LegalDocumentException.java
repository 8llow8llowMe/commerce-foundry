package com.commercefoundry.domainlayer.legaldocument.application.exception;

import lombok.Getter;

@Getter
public class LegalDocumentException extends RuntimeException {
    private final LegalDocumentErrorCode errorCode;

    public LegalDocumentException(LegalDocumentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public LegalDocumentException(LegalDocumentErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public LegalDocumentException(LegalDocumentErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
