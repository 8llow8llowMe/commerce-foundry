package com.commercefoundry.domainlayer.guestsession.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 고정 메시지만 내린다 — 토큰·지문은 예외 메시지에 실리지 않는다. 본문·파라미터가 없어 검증 핸들러는 두지 않는다. */
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.guestsession.adapter.in.web")
public class GuestSessionExceptionHandler {
    @ExceptionHandler(GuestSessionException.class)
    public ResponseEntity<Response<Void>> guest(GuestSessionException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Response.fail(code.getCode(), exception.getMessage()));
    }
}
