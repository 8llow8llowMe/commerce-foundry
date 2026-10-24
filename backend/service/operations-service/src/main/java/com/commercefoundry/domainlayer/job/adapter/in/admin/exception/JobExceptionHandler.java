package com.commercefoundry.domainlayer.job.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * job 컨텍스트 컨트롤러(어드민 작업 API)의 작업 도메인 · 요청 검증 오류를 {@code Response.fail} 봉투로 바꾼다. 범위를 job 패키지로 좁힌다 —
 * 알림 센터(#213) 같은 다른 컨텍스트는 자기 advice 를 두고, 그 컨텍스트 Facade 가 작업을 등록하다 받은 {@code JobException} 은 그쪽 핸들러가
 * 자기 코드로 번역한다 ({@code architecture-guide.md} §3). 여기서 서비스 전체를 받으면 빠진 번역이 {@code JOB_} 코드로 조용히 나간다.
 * 이 컨텍스트에는 고객 진입({@code in/web})이 없어 어드민 진입 아래 둔다.
 *
 * <p>{@code Exception} · {@code RuntimeException} 을 통째로 잡지 않는다 — {@code @PreAuthorize} 거부는 security-core 의
 * {@code SecurityExceptionAdvice}(가장 높은 우선순위)가 받는다 ({@code coding-conventions.md} §8-1, {@code security-guide.md} §5-2).
 * 지금 API 는 본문이 없어 본문 검증 · 읽기 실패 핸들러를 두지 않는다.
 */
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.job")
public class JobExceptionHandler {

    @ExceptionHandler(JobException.class)
    public ResponseEntity<Response<Void>> handleJobException(JobException exception) {
        JobErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    /** {@code @RequestParam} · {@code @PathVariable} 의 Bean Validation 실패 (컨트롤러 메서드 검증). 코드는 {@code JobValidationMessage} 접두어를 따른다. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return ValidationErrorSupport.toResponse(exception, JobErrorCode.INVALID_REQUEST.getCode());
    }

    /** 없는 상태 값({@code status=DONE}) · 숫자가 아닌 아이디. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ValidationErrorSupport.toResponse(exception, JobErrorCode.PARAMETER_TYPE_INVALID.getCode());
    }
}
