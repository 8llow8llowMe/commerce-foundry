package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StockAlertErrorCode {

    NOT_FOUND_SKU("STOCK_ALERT_001", "존재하지 않거나 삭제된 상품의 SKU입니다.", HttpStatus.NOT_FOUND),
    POLICY_BUSY("STOCK_ALERT_002", "다른 SKU 변경을 처리 중입니다. 다시 시도해 주세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
