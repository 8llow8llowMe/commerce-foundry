package com.commercefoundry.domainlayer.productdraftgeneration.application.exception;

import lombok.Getter;

@Getter
public class ProductDraftGenerationException extends RuntimeException {
    private final ProductDraftGenerationErrorCode errorCode;

    public ProductDraftGenerationException(ProductDraftGenerationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ProductDraftGenerationException(ProductDraftGenerationErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public ProductDraftGenerationException(ProductDraftGenerationErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
