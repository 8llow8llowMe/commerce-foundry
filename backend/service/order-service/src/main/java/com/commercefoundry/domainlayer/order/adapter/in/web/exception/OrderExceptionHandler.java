package com.commercefoundry.domainlayer.order.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogException;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import lombok.extern.slf4j.Slf4j;
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
 * 주문 컨트롤러의 도메인 · 요청 검증 오류를 {@code Response.fail} 봉투({@code ORDER_*})로 바꾼다. 범위를 주문 컨텍스트로 좁혀 둔다 —
 * 다른 컨텍스트(장바구니)의 요청 오류가 {@code ORDER_*} 코드로 나가지 않는다.
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core 의
 * {@code SecurityExceptionAdvice}(가장 높은 우선순위)가 받는다 ({@code coding-conventions.md} §8-1, {@code security-guide.md} §5-2).
 *
 * <p>로그에 요청 본문(이름 · 연락처 · 주소 · 맞춤 입력값)을 남기지 않는다. 원인 예외를 throwable 로 로그하지 않는다 — 서비스 간 호출 실패의 원인({@code FeignException})
 * 메시지에는 대상 응답 본문이, DB 제약 위반에는 행 값이 들어 있다 ({@code coding-conventions.md} §10). 코드와 원인 예외 타입만 남긴다.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.order")
public class OrderExceptionHandler {

    @ExceptionHandler(OrderException.class)
    public ResponseEntity<Response<Void>> handleOrderException(OrderException exception) {
        OrderErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.warn("order request failed. code={}, causeType={}", errorCode.getCode(), causeTypeOf(exception));
        }
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    /**
     * catalog 컨텍스트(SKU 조회)의 실패를 주문 코드로 번역한다 — catalog 는 자기 advice 가 없고, 주문 API 계약은 {@code ORDER_002}(503)이다
     * ({@code architecture-guide.md} §3).
     */
    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<Response<Void>> handleCatalogException(CatalogException exception) {
        OrderErrorCode errorCode = OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE;
        log.warn("order request failed. code={}, cause={}, causeType={}", errorCode.getCode(), exception.getErrorCode().getCode(), causeTypeOf(exception));
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /** {@code @RequestBody @Valid} 실패. 코드는 {@code OrderValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, OrderErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 타입 불일치 · 정수 범위 초과 · 모르는 통화. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleNotReadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, OrderErrorCode.INVALID_REQUEST.getCode());
    }

    /** 메서드 파라미터 제약 — 지금 주문 API 에는 없지만 경로 · 쿼리 파라미터가 생기면 여기로 온다. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, OrderErrorCode.INVALID_REQUEST.getCode());
    }

    /** 바인딩 단계의 형식 오류 — {@code Idempotency-Key} 가 UUID 가 아니면 여기서 {@code ORDER_198} 이다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, OrderErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), OrderErrorCode.PARAMETER_REQUIRED.getCode());
    }

    /** 필수 헤더 누락 — {@code Idempotency-Key} 가 없으면 {@code ORDER_199} 이고 {@code fieldErrors[0].field} 가 헤더 이름이다. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Response<Void>> handleMissingHeader(MissingRequestHeaderException exception) {
        return ValidationErrorSupport.toMissingParameterResponse(exception.getHeaderName(), OrderErrorCode.PARAMETER_REQUIRED.getCode());
    }

    private static String causeTypeOf(Throwable exception) {
        return exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName();
    }
}
