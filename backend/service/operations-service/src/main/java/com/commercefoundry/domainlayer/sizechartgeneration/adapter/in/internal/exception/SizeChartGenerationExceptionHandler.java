package com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.sizechartgeneration")
public class SizeChartGenerationExceptionHandler {
    @ExceptionHandler(SizeChartGenerationException.class)
    public ResponseEntity<Response<Void>> handle(SizeChartGenerationException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).cacheControl(CacheControl.noStore()).body(Response.fail(code.getCode(), code.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> invalid(MethodArgumentNotValidException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, "SIZE_CHART_GENERATION_100"));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> invalid(HandlerMethodValidationException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, "SIZE_CHART_GENERATION_100"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> malformed(HttpMessageNotReadableException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, "SIZE_CHART_GENERATION_100"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> malformed(MethodArgumentTypeMismatchException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, "SIZE_CHART_GENERATION_100"));
    }

    private ResponseEntity<Response<Void>> noStore(ResponseEntity<Response<Void>> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
