package com.commercefoundry.domainlayer.paymentobservation.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.paymentobservation.application.exception.PaymentObservationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.paymentobservation")
public class PaymentObservationExceptionHandler {

    @ExceptionHandler(PaymentObservationException.class)
    public ResponseEntity<Response<Void>> handlePaymentObservation(PaymentObservationException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), code.getMessage()));
    }
}
