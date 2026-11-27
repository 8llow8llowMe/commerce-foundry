package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.controller.LegalDraftGenerationController;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftErrorCode;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = LegalDraftGenerationController.class)
public class LegalDraftGenerationExceptionHandler {
    @ExceptionHandler(LegalDraftException.class)
    public ResponseEntity<Response<Void>> handle(LegalDraftException exception) {
        return failure(exception.getErrorCode());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Response<Void>> invalidRequest() {
        return failure(LegalDraftErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(JobException.class)
    public ResponseEntity<Response<Void>> handleJob(JobException exception) {
        var code = switch (exception.getErrorCode()) {
            case NOT_FOUND_JOB -> LegalDraftErrorCode.NOT_FOUND;
            case JOB_CONFLICT -> LegalDraftErrorCode.CANCEL_CONFLICT;
            default -> LegalDraftErrorCode.RESULT_UNAVAILABLE;
        };
        return failure(code);
    }

    private ResponseEntity<Response<Void>> failure(LegalDraftErrorCode code) {
        return ResponseEntity.status(code.getStatus()).cacheControl(CacheControl.noStore())
            .body(Response.fail(code.getCode(), code.getMessage()));
    }
}
