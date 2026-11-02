package com.commercefoundry.domainlayer.payment.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.ValidationErrorItem;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.payment.adapter.in.web.controller.PaymentWebController;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentRetryLaterException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentValidationMessage;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 결제 컨트롤러의 업무 · 요청 검증 오류를 {@code Response.fail} 봉투({@code PAYMENT_*})로 바꾼다 — payment-service 의 유일한 요청 검증 advice 다 ({@code coding-conventions.md} §8-1).
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core 의 {@code SecurityExceptionAdvice} 가 받는다.
 *
 * <p>로그 규칙 ({@code coding-conventions.md} §8): 결제키 · 요청 본문 · 원인 예외 메시지를 남기지 않는다. 5xx 와 번역되지 않은 도메인 예외는 코드와 원인 예외 타입
 * ({@code causeType}, 단순 클래스명)만 남기고 예외를 throwable 로 넘기지 않는다 — 원인 메시지에 DB 행 값 · 대상 응답 본문이, 도메인 예외 메시지에 상태 · 아이디가 실린다.
 * 응답 메시지는 언제나 오류 코드의 고객 문구다. 예외 메시지(포맷 인자 · 원인)를 응답에 쓰지 않는다 — {@code errorCode.getMessage()} 만 싣는다.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.payment")
public class PaymentExceptionHandler {

    private static final String CODE_DELIMITER = ":";

    /** 같은 요청을 잠시 뒤 다시 보내면 되는 오류 — {@code Retry-After}(초, 최소 1)를 싣는다. */
    @ExceptionHandler(PaymentRetryLaterException.class)
    public ResponseEntity<Response<Void>> handleRetryLater(PaymentRetryLaterException exception) {
        PaymentErrorCode errorCode = exception.getErrorCode();
        long millis = exception.getRetryAfter().toMillis();
        long seconds = Math.max(1, (millis + 999) / 1000);
        log.warn("payment request failed. code={}, retryAfterSeconds={}", errorCode.getCode(), seconds);
        return ResponseEntity.status(errorCode.getHttpStatus())
            .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<Response<Void>> handlePaymentException(PaymentException exception) {
        PaymentErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.warn("payment request failed. code={}, causeType={}", errorCode.getCode(), causeTypeOf(exception));
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(errorCode.getHttpStatus());
        if (errorCode == PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE) {
            // 재요청 간격 안내다. 서킷 복구를 보장하거나 승인 요청을 자동 재전송하지 않는다.
            response.header(HttpHeaders.RETRY_AFTER, "10");
        }
        return response.body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /**
     * 마지막 안전망 — Facade 가 번역하지 못한 전이 예외 · 승인 불일치 · 저장 충돌. 요청이 겹친 것으로 보고 409 {@code PAYMENT_012} 로 답한다.
     * 번역이 빠진 코드 경로라 error 로그를 남긴다. 예외 메시지(상태 · 아이디)는 응답 · 로그에 싣지 않는다.
     */
    @ExceptionHandler({PaymentTransitionException.class, PaymentApprovalMismatchException.class, PaymentPersistenceConflictException.class})
    public ResponseEntity<Response<Void>> handleUntranslated(RuntimeException exception) {
        PaymentErrorCode errorCode = PaymentErrorCode.PAYMENT_REQUEST_CONFLICT;
        log.error("payment request hit untranslated domain exception. code={}, causeType={}", errorCode.getCode(), exception.getClass().getSimpleName());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /** {@code @RequestBody @Valid} 실패. 코드는 {@code PaymentValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, PaymentErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 타입 불일치 · 정수 범위 초과. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleNotReadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, PaymentErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, PaymentErrorCode.INVALID_REQUEST.getCode());
    }

    /** 바인딩 단계의 형식 오류 — {@code Idempotency-Key} 가 UUID 가 아니면 {@code PAYMENT_114}, 경로 변수(결제 아이디) 형식 오류는 {@code PAYMENT_198}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        if (PaymentWebController.IDEMPOTENCY_KEY_HEADER.equals(exception.getName())) {
            return fieldError(PaymentValidationMessage.IDEMPOTENCY_KEY_FORMAT_INVALID, PaymentWebController.IDEMPOTENCY_KEY_HEADER);
        }
        return ValidationErrorSupport.toResponse(exception, PaymentErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), PaymentErrorCode.PARAMETER_REQUIRED.getCode());
    }

    /** 필수 헤더 누락 — {@code Idempotency-Key} 면 {@code PAYMENT_113}, 그 밖의 헤더는 {@code PAYMENT_199}. {@code fieldErrors[0].field} 가 헤더 이름이다. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Response<Void>> handleMissingHeader(MissingRequestHeaderException exception) {
        if (PaymentWebController.IDEMPOTENCY_KEY_HEADER.equals(exception.getHeaderName())) {
            return fieldError(PaymentValidationMessage.IDEMPOTENCY_KEY_REQUIRED, PaymentWebController.IDEMPOTENCY_KEY_HEADER);
        }
        return ValidationErrorSupport.toMissingParameterResponse(exception.getHeaderName(), PaymentErrorCode.PARAMETER_REQUIRED.getCode());
    }

    /** {@code "PAYMENT_1xx:메시지"} 상수를 필드 오류 하나짜리 봉투로 만든다. */
    private static ResponseEntity<Response<Void>> fieldError(String validationMessage, String field) {
        int delimiter = validationMessage.indexOf(CODE_DELIMITER);
        String code = validationMessage.substring(0, delimiter);
        String message = validationMessage.substring(delimiter + 1);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Response.fail(code, message, List.of(new ValidationErrorItem(code, field, message))));
    }

    private static String causeTypeOf(Throwable exception) {
        return exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName();
    }
}
