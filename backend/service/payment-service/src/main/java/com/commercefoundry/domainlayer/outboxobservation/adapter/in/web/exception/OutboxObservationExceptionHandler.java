package com.commercefoundry.domainlayer.outboxobservation.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.outboxobservation.application.exception.OutboxObservationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.outboxobservation")
public class OutboxObservationExceptionHandler {

    @ExceptionHandler(OutboxObservationException.class)
    public ResponseEntity<Response<Void>> handleOutboxObservation(OutboxObservationException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), code.getMessage()));
    }
}
