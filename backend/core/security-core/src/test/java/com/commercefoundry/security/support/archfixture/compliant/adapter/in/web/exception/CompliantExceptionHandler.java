package com.commercefoundry.security.support.archfixture.compliant.adapter.in.web.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 준수 예시 — {@code @RestControllerAdvice} 는 컨트롤러가 아니고 {@code @ExceptionHandler} 는 핸들러 메서드가 아니다. 규칙 대상이 아니다. */
@RestControllerAdvice
public class CompliantExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handle(IllegalStateException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}
