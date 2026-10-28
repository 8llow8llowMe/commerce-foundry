package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
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
 * onboarding 컨텍스트 어드민 API 의 오류를 {@code Response.fail} 봉투({@code ONBOARDING_*})로 바꾼다. 범위를 이 컨텍스트로 좁힌다 — job · notification 오류를 받지 않는다.
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core 의
 * {@code SecurityExceptionAdvice}(가장 높은 우선순위)가 받는다 ({@code coding-conventions.md} §8-1). 버전 충돌은 저장소 어댑터가
 * {@code ONBOARDING_021} 로 바꿔 올리므로 {@code OptimisticLockingFailureException} 핸들러를 두지 않는다.
 */
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.onboarding")
public class OnboardingExceptionHandler {

    @ExceptionHandler(OnboardingException.class)
    public ResponseEntity<Response<Void>> handleOnboardingException(OnboardingException exception) {
        OnboardingErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    /** {@code @RequestBody @Valid} 실패 — 경로 검증이 함께 걸린 메서드는 아래 메서드 검증 예외로 온다. 코드는 {@code OnboardingValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, OnboardingErrorCode.INVALID_REQUEST.getCode());
    }

    /**
     * 경로 변수({@code guideKey} 형식)와 {@code @RequestBody @Valid} 가 한 메서드에 함께 있어 Spring 이 본문 검증까지 메서드 검증으로 묶어 던진다.
     * 그대로 넘기면 본문 필드 오류의 필드명이 파라미터 이름({@code request})으로 뭉개지므로, 본문 결과가 있으면 {@link MethodArgumentNotValidException} 과
     * 같은 모양(본문 필드명 · record 선언 순서)으로 바꾸고 경로 변수 오류를 함께 싣는다 ({@code coding-conventions.md} §8-2, product 의 같은 핸들러와 같은 방식).
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        if (exception.getBeanResults().isEmpty()) {
            return ValidationErrorSupport.toResponse(exception, OnboardingErrorCode.INVALID_REQUEST.getCode());
        }
        return ValidationErrorSupport.toResponse(asBodyValidationException(exception), OnboardingErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 범위 밖 enum({@code status: "DONE"}) · 타입 불일치 · 정수 범위 초과. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleNotReadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, OnboardingErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, OnboardingErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), OnboardingErrorCode.PARAMETER_REQUIRED.getCode());
    }

    /**
     * 메서드 검증 결과를 본문 검증 예외 모양으로 옮긴다. 본문(첫 bean 결과)의 필드 오류는 그대로, 경로 변수 오류는 파라미터 이름을 필드명으로 한 필드 오류로 더한다.
     * 메시지 · 제약 코드는 원래 오류의 것을 쓴다.
     *
     * <p>값 파라미터는 새 {@link FieldError} 라 원본 위반이 빠지면 {@code @ConstraintPriority} 를 읽지 못하고 맨 뒤에 선다 — {@code wrap} 으로 원본을 싣는다.
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

    /** 값 파라미터 오류에 원본 {@link ConstraintViolation} 을 싣는다. 전역 오류는 결과의 unwrap 이 깨지므로 오류가 이미 품은 위반만 옮긴다. */
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
