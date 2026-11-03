package com.commercefoundry.domainlayer.payment.adapter.in.webhook.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.payment.adapter.in.webhook")
public class PaymentWebhookExceptionHandler {
    @ExceptionHandler(PaymentWebhookException.class)
    public ResponseEntity<Response<Void>> failure(PaymentWebhookException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), code.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> invalid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, PaymentWebhookErrorCode.INVALID.getCode());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> malformed(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, PaymentWebhookErrorCode.INVALID.getCode());
    }
}
