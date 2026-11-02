package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.mediaupload.adapter.in.admin")
public class MediaUploadExceptionHandler {
    @ExceptionHandler(MediaUploadException.class)
    public ResponseEntity<Response<Void>> handleDomain(MediaUploadException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), code.getMessage()));
    }
}
