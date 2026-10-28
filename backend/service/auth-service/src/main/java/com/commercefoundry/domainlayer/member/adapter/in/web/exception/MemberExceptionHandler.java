package com.commercefoundry.domainlayer.member.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 회원 도메인 오류와 <b>auth-service 전체</b>의 요청 검증 오류를 {@code Response.fail} 봉투로 바꾼다.
 *
 * <p>검증 예외(본문 · 파라미터 · 타입 불일치 · 필수 파라미터)는 컨텍스트(회원 · 배송지)와 무관하게 여기 하나가 받는다 — 검증 메시지가
 * 코드({@code AUTH_1xx:...})를 싣고 오므로 컨텍스트를 몰라도 되고, 폴백 · 대역 끝 코드(AUTH_100 · 198 · 199)는 서비스 공용이다.
 * 다른 컨텍스트의 advice({@code ShippingAddressExceptionHandler})는 자기 도메인 예외만 잡아 같은 예외를 두 advice 가 다투지 않는다.
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core 의
 * {@code SecurityExceptionAdvice}(가장 높은 우선순위)가 받는다 ({@code coding-conventions.md} §8-1, {@code security-guide.md} §5-2).
 * 이 advice 는 범위가 넓은 쪽이라 {@code @Order} 를 두지 않는다(가장 낮은 우선순위) — 좁은 advice 가 {@code @Order} 를 단다.
 */
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class MemberExceptionHandler {

    @ExceptionHandler(MemberException.class)
    public ResponseEntity<Response<Void>> handleMemberException(MemberException exception) {
        MemberErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    /**
     * 시도 제한 초과 (#66) — 429 {@code AUTH_040} · {@code AUTH_041} 봉투에 {@code Retry-After}(초, {@link #retryAfterSeconds})를 붙인다.
     * 게이트웨이 {@code GATEWAY_006} 과 같은 모양이다. 본문 · 헤더는 계정 존재와 무관하다 — 남은 창 시간만 싣는다.
     */
    @ExceptionHandler(LoginAttemptLimitedException.class)
    public ResponseEntity<Response<Void>> handleLoginAttemptLimited(LoginAttemptLimitedException exception) {
        MemberErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(exception.getRetryAfter())))
            .body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    /**
     * {@code Retry-After} 헤더 값(초) — {@code max(1, ceil(ms / 1000))}. 헤더 형식이라 예외가 아니라 여기서 정한다 ({@code coding-conventions.md} §8-1).
     * 창 끝과 같은 시각(남은 0ms)도 1초다: 0 이면 클라이언트가 바로 다시 보내 같은 창에 부딪힌다.
     */
    private static long retryAfterSeconds(Duration retryAfter) {
        long millis = Math.max(0, retryAfter.toMillis());
        return Math.max(1, (millis + 999) / 1000);
    }

    /** {@code @Valid @RequestBody} 검증 실패. 코드는 {@code MemberValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.INVALID_REQUEST.getCode());
    }

    /** 깨진 JSON · 본문 없음 · 타입 불일치(boolean 자리에 문자열 등). 필드별 코드가 없어 {@code AUTH_100} 이고 필드는 밝힌다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleNotReadable(HttpMessageNotReadableException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.INVALID_REQUEST.getCode());
    }

    /** {@code @RequestParam} 의 Bean Validation 실패 (컨트롤러 메서드 검증). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.INVALID_REQUEST.getCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, MemberErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), MemberErrorCode.PARAMETER_REQUIRED.getCode());
    }
}
