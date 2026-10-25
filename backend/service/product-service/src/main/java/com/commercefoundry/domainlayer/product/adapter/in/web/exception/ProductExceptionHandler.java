package com.commercefoundry.domainlayer.product.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import jakarta.validation.ConstraintViolation;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * product-service 컨트롤러(카테고리 · 상품 · 어드민 재고 · internal SKU · internal 재고 선점)의 도메인 · 요청 검증 오류를 {@code Response.fail} 봉투로 바꾼다.
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

    /**
     * {@code @RequestParam} · {@code @PathVariable} 의 Bean Validation 실패 (컨트롤러 메서드 검증). 코드는 {@code ProductValidationMessage} 접두어를 따른다.
     *
     * <p>한 메서드에 제약 붙은 경로 변수와 {@code @RequestBody @Valid} 가 함께 있으면(어드민 재고 조정) Spring 이 본문 검증까지 메서드 검증으로 묶어
     * 이 예외로 던진다. 그대로 넘기면 본문 필드 오류의 필드명이 파라미터 이름({@code request})으로 뭉개지므로, 본문 결과가 있으면
     * {@link MethodArgumentNotValidException} 과 같은 모양(필드명 · record 선언 순서)으로 바꾸고 경로 변수 오류를 함께 싣는다.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        if (exception.getBeanResults().isEmpty()) {
            return ValidationErrorSupport.toResponse(exception, ProductErrorCode.INVALID_REQUEST.getCode());
        }
        return ValidationErrorSupport.toResponse(asBodyValidationException(exception), ProductErrorCode.INVALID_REQUEST.getCode());
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

    /**
     * 메서드 검증 결과를 본문 검증 예외 모양으로 옮긴다. 본문(첫 bean 결과)의 필드 오류는 그대로, 경로 변수 · 쿼리 파라미터 오류는
     * 파라미터 이름을 필드명으로 한 필드 오류로 더한다. 메시지 · 제약 코드는 원래 오류의 것을 쓴다.
     *
     * <p>값 파라미터는 새 {@link FieldError} 라 원본 위반이 빠지면 {@code @ConstraintPriority} 를 읽지 못하고 맨 뒤(9)에 선다.
     * {@code wrap} 으로 원본을 싣는다. 전역 {@link ObjectError} 는 {@code result.unwrap} 이 {@code FieldError} 로 캐스트해 깨지므로
     * 오류 자신에게 묻고, 원본이 없으면 제약 이름으로만 정렬한다 ({@code coding-conventions.md} §8-2).
     */
    private static MethodArgumentNotValidException asBodyValidationException(HandlerMethodValidationException exception) {
        ParameterErrors body = exception.getBeanResults().getFirst();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(body.getArgument(), body.getObjectName());
        exception.getBeanResults().forEach(result -> result.getAllErrors().forEach(bindingResult::addError));
        exception.getValueResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> {
                FieldError fieldError = new FieldError(
                    body.getObjectName(), field == null ? "request" : field, result.getArgument(), false,
                    error.getCodes(), error.getArguments(), error.getDefaultMessage()
                );
                wrapOriginalViolation(result, error, fieldError);
                bindingResult.addError(fieldError);
            });
        });
        return new MethodArgumentNotValidException(body.getMethodParameter(), bindingResult);
    }

    /**
     * 값 파라미터 오류에 원본 {@link ConstraintViolation} 을 싣는다. 전역 오류는 결과의 unwrap 이 깨지므로 오류가 이미 품은 위반만 옮긴다.
     */
    private static void wrapOriginalViolation(ParameterValidationResult result, MessageSourceResolvable error, FieldError fieldError) {
        if (error instanceof ObjectError objectError) {
            if (objectError.contains(ConstraintViolation.class)) {
                fieldError.wrap(objectError.unwrap(ConstraintViolation.class));
            }
            return;
        }
        try {
            fieldError.wrap(result.unwrap(error, ConstraintViolation.class));
        } catch (IllegalArgumentException noViolation) {
            // 호출부가 직접 만든 오류다. 제약 이름으로만 자리를 판정한다.
        }
    }
}
