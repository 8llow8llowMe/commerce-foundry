package com.commercefoundry.domainlayer.catalog.application.exception;

import lombok.Getter;

@Getter
public class CatalogException extends RuntimeException {

    private final CatalogErrorCode errorCode;

    public CatalogException(CatalogErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public CatalogException(CatalogErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public CatalogException(CatalogErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
