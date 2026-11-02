package com.commercefoundry.domainlayer.product.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 정책 업무 오류만 처리한다. 요청 검증은 서비스 공용 ProductExceptionHandler에 위임한다. */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.product")
public class StockAlertExceptionHandler {

    @ExceptionHandler(StockAlertException.class)
    public ResponseEntity<Response<Void>> handleStockAlertException(StockAlertException exception) {
        StockAlertErrorCode code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), exception.getMessage()));
    }
}
