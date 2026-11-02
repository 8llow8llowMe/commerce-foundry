package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;

@Getter
public class StockAlertException extends RuntimeException {

    private final StockAlertErrorCode errorCode;

    public StockAlertException(StockAlertErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public StockAlertException(StockAlertErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public StockAlertException(StockAlertErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
