package com.commercefoundry.domainlayer.settlement.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 정산 컨트롤러의 업무 · 요청 검증 오류를 {@code Response.fail} 봉투로 바꾼다. 범위는 settlement 패키지뿐이다 — payment 의 요청 검증 advice
 * ({@code PaymentExceptionHandler}, 범위 {@code domainlayer.payment})와 같은 컨트롤러를 두고 겹치지 않는다. 폴백 코드(깨진 JSON · 형식 오류)는
 * 서비스 공용 {@code PAYMENT_100} 이다 ({@code coding-conventions.md} §8-2).
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core advice 가 받는다.
 * 5xx 로그는 코드와 원인 타입만 남긴다(원인 메시지 · 결제키를 남기지 않는다).
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.settlement")
public class SettlementExceptionHandler {

    @ExceptionHandler(SettlementException.class)
    public ResponseEntity<Response<Void>> handleSettlement(SettlementException exception) {
        SettlementErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.warn("settlement request failed. code={}, causeType={}", errorCode.getCode(),
                exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
        }
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /** {@code @RequestBody @Valid} 실패 — 코드는 {@code SettlementValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, PaymentErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 날짜 형식 오류(yyyy-MM-dd 아님 · 없는 날짜). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleNotReadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, PaymentErrorCode.INVALID_REQUEST.getCode());
    }
}
