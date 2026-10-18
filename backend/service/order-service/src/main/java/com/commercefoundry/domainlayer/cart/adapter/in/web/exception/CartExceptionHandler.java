package com.commercefoundry.domainlayer.cart.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogErrorCode;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 장바구니 컨트롤러의 도메인 · 요청 검증 오류를 {@code Response.fail} 봉투({@code CART_*})로 바꾼다. 범위를 장바구니 컨텍스트로 좁혀 둔다 —
 * 다른 컨텍스트(주문 등)의 요청 오류가 {@code CART_*} 코드로 나가지 않고, 그 컨텍스트가 자기 advice 를 둔다.
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core 의
 * {@code SecurityExceptionAdvice}(가장 높은 우선순위)가 받는다 ({@code coding-conventions.md} §8-1, {@code security-guide.md} §5-2).
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.cart")
public class CartExceptionHandler {

    @ExceptionHandler(CartException.class)
    public ResponseEntity<Response<Void>> handleCartException(CartException exception) {
        CartErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.warn("cart request failed. code={}", errorCode.getCode(), exception);
        }
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    /**
     * catalog 컨텍스트(SKU 조회)의 실패를 장바구니 코드로 번역한다 — catalog 는 자기 advice 가 없고, 장바구니 API 계약은 {@code CART_008}(503)이다
     * ({@code architecture-guide.md} §3). {@link CatalogErrorCode} 는 지금 503 하나뿐이라 모두 {@code CART_PRODUCT_SERVICE_UNAVAILABLE} 이다.
     */
    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<Response<Void>> handleCatalogException(CatalogException exception) {
        CartErrorCode errorCode = CartErrorCode.CART_PRODUCT_SERVICE_UNAVAILABLE;
        log.warn("cart request failed. code={}, cause={}", errorCode.getCode(), exception.getErrorCode().getCode(), exception);
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /** {@code @RequestBody @Valid} 실패. 코드는 {@code CartValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, CartErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 타입 불일치({@code quantity: "a"}) · 정수 범위 초과. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleNotReadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, CartErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, CartErrorCode.INVALID_REQUEST.getCode());
    }

    /** 경로 아이디에 숫자가 아닌 값 등 바인딩 단계의 형식 오류. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, CartErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), CartErrorCode.PARAMETER_REQUIRED.getCode());
    }
}
