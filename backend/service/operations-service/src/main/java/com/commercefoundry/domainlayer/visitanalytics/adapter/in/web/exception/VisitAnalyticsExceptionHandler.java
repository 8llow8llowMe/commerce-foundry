package com.commercefoundry.domainlayer.visitanalytics.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.visitanalytics.application.exception.VisitAnalyticsException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.visitanalytics")
public class VisitAnalyticsExceptionHandler {

    @ExceptionHandler(VisitAnalyticsException.class)
    public ResponseEntity<Response<Void>> handle(VisitAnalyticsException exception) {
        var error = exception.getErrorCode();
        return ResponseEntity.status(error.getHttpStatus()).cacheControl(CacheControl.noStore())
            .body(Response.fail(error.getCode(), error.getMessage()));
    }
}
