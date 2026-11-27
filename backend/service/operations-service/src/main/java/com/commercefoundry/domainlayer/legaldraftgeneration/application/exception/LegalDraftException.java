package com.commercefoundry.domainlayer.legaldraftgeneration.application.exception;

import lombok.Getter;

@Getter
public class LegalDraftException extends RuntimeException {
    private final LegalDraftErrorCode errorCode;

    public LegalDraftException(LegalDraftErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
