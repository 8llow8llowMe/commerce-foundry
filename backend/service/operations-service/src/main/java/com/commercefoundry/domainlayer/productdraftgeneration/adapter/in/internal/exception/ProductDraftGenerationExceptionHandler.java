package com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.productdraftgeneration")
public class ProductDraftGenerationExceptionHandler {
    private static final String INVALID_REQUEST = ProductDraftGenerationErrorCode.INVALID_REQUEST.getCode();

    @ExceptionHandler(ProductDraftGenerationException.class)
    public ResponseEntity<Response<Void>> handle(ProductDraftGenerationException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).cacheControl(CacheControl.noStore()).body(Response.fail(code.getCode(), code.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> invalid(MethodArgumentNotValidException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, INVALID_REQUEST));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> invalid(HandlerMethodValidationException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, INVALID_REQUEST));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> malformed(HttpMessageNotReadableException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, INVALID_REQUEST));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> malformed(MethodArgumentTypeMismatchException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, INVALID_REQUEST));
    }

    private ResponseEntity<Response<Void>> noStore(ResponseEntity<Response<Void>> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
