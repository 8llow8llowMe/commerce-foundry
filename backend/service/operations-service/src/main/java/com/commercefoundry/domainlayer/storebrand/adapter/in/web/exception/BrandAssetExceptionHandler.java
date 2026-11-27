package com.commercefoundry.domainlayer.storebrand.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.storebrand")
public class BrandAssetExceptionHandler {
    @ExceptionHandler(BrandAssetException.class)
    public ResponseEntity<Response<Void>> handle(BrandAssetException exception) {
        Code code = exception.getCode();
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
            .body(Response.fail(code.getValue(), code.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Response<Void>> invalidRequest() {
        return handle(new BrandAssetException(Code.INVALID_REQUEST));
    }
}
