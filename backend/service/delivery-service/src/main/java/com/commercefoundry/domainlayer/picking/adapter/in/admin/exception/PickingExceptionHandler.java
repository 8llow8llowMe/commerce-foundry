package com.commercefoundry.domainlayer.picking.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.picking")
public class PickingExceptionHandler {
    @ExceptionHandler(PickingException.class)
    public ResponseEntity<Response<Void>> handle(PickingException exception) {
        PickingErrorCode code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), exception.getMessage()));
    }
}
