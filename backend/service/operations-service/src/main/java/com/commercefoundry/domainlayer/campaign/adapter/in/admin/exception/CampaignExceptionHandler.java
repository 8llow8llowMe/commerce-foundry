package com.commercefoundry.domainlayer.campaign.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignErrorCode;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.campaign")
public class CampaignExceptionHandler {
    @ExceptionHandler(CampaignException.class)
    public ResponseEntity<Response<Void>> handleCampaign(CampaignException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), code.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, CampaignErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, CampaignErrorCode.INVALID_REQUEST.getCode());
    }
}
