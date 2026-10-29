package com.commercefoundry.domainlayer.skucost.application.exception;

import lombok.Getter;

@Getter
public class SkuCostException extends RuntimeException {

    private final SkuCostErrorCode errorCode;

    public SkuCostException(SkuCostErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. 금액을 넘기지 않는다. */
    public SkuCostException(SkuCostErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public SkuCostException(SkuCostErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
