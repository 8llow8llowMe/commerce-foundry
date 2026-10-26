package com.commercefoundry.domainlayer.notification.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * notification 컨텍스트 어드민 API 의 오류를 봉투로 바꾼다. 범위를 이 패키지의 컨트롤러로 좁힌다 — job 컨텍스트 오류를 여기서 받지 않는다.
 */
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.notification.adapter.in.admin")
public class NotificationExceptionHandler {

    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<Response<Void>> handleNotificationException(NotificationException exception) {
        NotificationErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Response<Void>> handleConflict(OptimisticLockingFailureException exception) {
        NotificationErrorCode errorCode = NotificationErrorCode.CONFLICT;
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, NotificationErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, NotificationErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }
}
