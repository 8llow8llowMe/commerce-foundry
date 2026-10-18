package com.commercefoundry.domainlayer.product.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * product-service 컨트롤러(카테고리 · 상품 · 어드민 재고 · internal SKU)의 도메인 · 요청 검증 오류를 {@code Response.fail} 봉투로 바꾼다.
 * 카테고리 컨텍스트는 오류 경로가 없어 3종 세트를 따로 두지 않고, 같은 서비스의 이 advice 가 요청 바인딩 오류를 받는다.
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core 의
 * {@code SecurityExceptionAdvice}(가장 높은 우선순위)가 받는다 ({@code coding-conventions.md} §8-1, {@code security-guide.md} §5-2).
 * 이 advice 와 잡는 예외 타입이 겹치지 않아 {@code @Order} 를 두지 않는다. 서비스에 advice 가 하나 더 생기면 좁은 쪽에 {@code @Order} 를 단다.
 */
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class ProductExceptionHandler {

    @ExceptionHandler(ProductException.class)
    public ResponseEntity<Response<Void>> handleProductException(ProductException exception) {
        ProductErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    /** {@code @RequestBody @Valid} 의 Bean Validation 실패. 필드 · 교차 검증({@code @AssertTrue}) 코드는 {@code ProductValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, ProductErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · enum 에 없는 값 · 타입 불일치 · 정수 범위 초과. 역직렬화 단계라 필드별 코드 없이 {@code PRODUCT_100} 이다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleMessageNotReadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, ProductErrorCode.INVALID_REQUEST.getCode());
    }

    /** {@code @RequestParam} 의 Bean Validation 실패 (컨트롤러 메서드 검증). 코드는 {@code ProductValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, ProductErrorCode.INVALID_REQUEST.getCode());
    }

    /** 숫자 자리에 문자 · enum 에 없는 값({@code sortType=FOO}) 등 바인딩 단계의 형식 오류. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, ProductErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), ProductErrorCode.PARAMETER_REQUIRED.getCode());
    }
}
