package com.commercefoundry.domainlayer.productdraft.application.exception;

import lombok.Getter;

@Getter
public class ProductDraftException extends RuntimeException {

    private final ProductDraftErrorCode errorCode;

    public ProductDraftException(ProductDraftErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ProductDraftException(ProductDraftErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
