package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * delivery-service 컨트롤러(고객 · 어드민 · internal)의 도메인 · 요청 검증 오류를 {@code Response.fail} 봉투로 바꾼다. 서비스 공용이다 —
 * 송장 · 배송 추적 컨텍스트가 들어와도 advice 를 하나로 둔다.
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core 의
 * {@code SecurityExceptionAdvice}(가장 높은 우선순위)가 받는다 ({@code coding-conventions.md} §8-1, {@code security-guide.md} §5-2).
 * 이 advice 와 잡는 예외 타입이 겹치지 않아 {@code @Order} 를 두지 않는다. 서비스에 advice 가 하나 더 생기면 좁은 쪽에 {@code @Order} 를 단다.
 *
 * <p>정책 교체의 동시 저장 충돌({@code ObjectOptimisticLockingFailureException})은 여기서 잡지 않는다 — {@code ShippingPolicyProcessor} 가
 * 그 유스케이스 안에서만 {@code DELIVERY_006} 으로 바꾼다. 여기서 잡으면 다른 엔티티의 충돌도 배송비 정책 충돌로 읽힌다.
 */
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class DeliveryExceptionHandler {

    @ExceptionHandler(DeliveryException.class)
    public ResponseEntity<Response<Void>> handleDeliveryException(DeliveryException exception) {
        DeliveryErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    /** {@code @Valid @RequestBody} 의 Bean Validation 실패. 코드는 {@code DeliveryValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, DeliveryErrorCode.INVALID_REQUEST.getCode());
    }

    /** 본문을 읽지 못함 — 깨진 JSON, 없는 통화({@code "JPY"}), 숫자 자리에 문자. 어느 필드인지는 밝힌다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleMessageNotReadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, DeliveryErrorCode.INVALID_REQUEST.getCode());
    }

    /** {@code @RequestParam} · {@code @PathVariable} 의 Bean Validation 실패 (컨트롤러 메서드 검증). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, DeliveryErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, DeliveryErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), DeliveryErrorCode.PARAMETER_REQUIRED.getCode());
    }
}
