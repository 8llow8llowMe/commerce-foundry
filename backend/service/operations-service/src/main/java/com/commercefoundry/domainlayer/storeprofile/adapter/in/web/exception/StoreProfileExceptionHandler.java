package com.commercefoundry.domainlayer.storeprofile.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.storeprofile")
public class StoreProfileExceptionHandler {

    @ExceptionHandler(StoreProfileException.class)
    public ResponseEntity<Response<Void>> handle(StoreProfileException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).cacheControl(CacheControl.noStore())
            .body(Response.fail(code.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> invalid(MethodArgumentNotValidException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, "STORE_PROFILE_100"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> malformed(HttpMessageNotReadableException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, "STORE_PROFILE_100"));
    }

    private ResponseEntity<Response<Void>> noStore(ResponseEntity<Response<Void>> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
            .cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
