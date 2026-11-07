package com.commercefoundry.domainlayer.conversation.adapter.in.web.exception;

import static com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode.INVALID_REQUEST;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.conversation.adapter.in.web")
public class ConversationExceptionHandler {
    @ExceptionHandler(ConversationException.class)
    public ResponseEntity<Response<Void>> conversation(ConversationException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(GuestSessionException.class)
    public ResponseEntity<Response<Void>> guest(GuestSessionException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> unreadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> mismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, INVALID_REQUEST.getCode());
    }
}
