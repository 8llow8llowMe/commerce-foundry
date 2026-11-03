package com.commercefoundry.domainlayer.product.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 요청 바인딩 검증은 기존 ProductExceptionHandler가 처리한다. */
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.product.adapter.in.admin")
public class SkuAdminExceptionHandler {
    @ExceptionHandler(SkuAdminException.class)
    public ResponseEntity<Response<Void>> handle(SkuAdminException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), exception.getMessage()));
    }
}
