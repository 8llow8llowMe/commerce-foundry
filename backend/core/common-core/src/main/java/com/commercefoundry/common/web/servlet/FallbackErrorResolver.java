package com.commercefoundry.common.web.servlet;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.CommonErrorCode;
import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.common.log.LogPathMask;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도메인 · 보안 advice 가 잡지 않은 오류를 {@code Response.fail} 봉투로 쓴다 ({@code api-design-guide.md} §2-1 공통 오류 코드).
 * 없으면 매핑 없는 경로(404) · 메서드(405) · 미디어 타입(415) · 처리되지 않은 예외(500)가 Boot 기본 오류 JSON 으로 나가
 * BFF · 서비스 간 호출의 봉투 파서가 갈라진다. {@code DispatcherServlet} 에 닿기 전(필터 · 방화벽)의 오류는 {@link FallbackErrorController} 가 맡는다.
 *
 * <p><b>위치가 곧 우선순위다.</b> {@link FallbackErrorAutoConfiguration} 이 MVC 예외 처리 사슬에서 {@code @ExceptionHandler}
 * 처리기(모든 {@code @RestControllerAdvice}) <b>바로 뒤</b>, Spring 기본 처리기({@code ResponseStatusExceptionResolver} ·
 * {@code DefaultHandlerExceptionResolver}) <b>앞</b>에 넣는다. 그래서 도메인 advice 가 {@code @Order} 를 달지 않아도 언제나 먼저 받고,
 * 여기는 아무도 받지 않은 오류만 온다 — advice 로 {@code Exception} 을 잡으면 도메인 advice 와 같은 순위라 도메인 예외를 가로챌 수 있다.
 * 빈으로 등록하지 않는다 — 빈이면 {@code DispatcherServlet} 이 사슬 밖에서 한 번 더 찾아 순서가 흐려진다.
 *
 * <p>500 은 원인 · 스택을 응답에 싣지 않고 로그에만 남긴다. 로그에도 예외 메시지는 남기지 않고 원인 사슬의 타입 · 스택 프레임만 남긴다
 * ({@link RedactedThrowable}, #280). 로그 경로는 앞 {@value LogPathMask#VISIBLE_PATH_SEGMENTS} 구간만 남긴다 —
 * 주문번호 같은 식별자가 경로에 들어간다 (security-core {@code SecurityErrorResponder} · 게이트웨이와 같은 {@link LogPathMask}, {@code coding-conventions.md} §8).
 */
@Slf4j
public class FallbackErrorResolver implements HandlerExceptionResolver {

    private final JsonMapper jsonMapper;

    /** 서비스 컨텍스트의 {@code JsonMapper} 빈을 넘긴다. 설정이 같아야 다른 오류 응답과 모양이 같다. */
    public FallbackErrorResolver(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        // 클라이언트가 끊은 응답은 쓸 곳이 없다. Spring 기본 처리기가 조용히 끝내게 넘긴다
        if (exception instanceof AsyncRequestNotUsableException) {
            return null;
        }
        if (response.isCommitted()) {
            log.warn("fallback error response skipped since response is committed. method={}, path={}, exception={}",
                request.getMethod(), maskPath(request.getRequestURI()), exception.getClass().getName());
            return null;
        }

        HttpStatusCode status = statusOf(exception);
        CommonErrorCode errorCode = CommonErrorCode.of(status);
        log(request, status, errorCode, exception);
        try {
            write(jsonMapper, response, status, headersOf(exception), errorCode);
        } catch (IOException writeFailure) {
            log.warn("fallback error response write failed. code={}, method={}, path={}",
                errorCode.getCode(), request.getMethod(), maskPath(request.getRequestURI()), RedactedThrowable.of(writeFailure));
        }
        return new ModelAndView();
    }

    /**
     * 프레임워크 오류({@link ErrorResponse} — 404 · 405 · 415 · {@code ResponseStatusException} 등)는 그 상태, {@code @ResponseStatus} 예외는 선언된 상태, 나머지는 500.
     * {@code @ResponseStatus} 는 원인(cause)까지 따라가 찾는다 — 이 처리기가 앞에 서서 대신 받는 {@code ResponseStatusExceptionResolver} 와 같은 규칙이다.
     */
    private static HttpStatusCode statusOf(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            return errorResponse.getStatusCode();
        }
        for (Throwable current = exception; current != null; current = current.getCause() == current ? null : current.getCause()) {
            ResponseStatus declared = AnnotatedElementUtils.findMergedAnnotation(current.getClass(), ResponseStatus.class);
            if (declared != null) {
                return declared.code();
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** 405 의 {@code Allow}, 415 의 {@code Accept} 처럼 프레임워크가 정한 응답 헤더를 그대로 싣는다. */
    private static HttpHeaders headersOf(Exception exception) {
        return exception instanceof ErrorResponse errorResponse ? errorResponse.getHeaders() : HttpHeaders.EMPTY;
    }

    private static void log(HttpServletRequest request, HttpStatusCode status, CommonErrorCode errorCode, Exception exception) {
        String path = maskPath(request.getRequestURI());
        if (status.is5xxServerError() && exception instanceof ErrorResponse) {
            // 프레임워크가 상태를 정한 5xx(비동기 요청 시간 초과 503 등)는 결함이 아니라 운영 신호라 스택 없이 남긴다
            log.warn("framework 5xx answered by fallback envelope. code={}, status={}, method={}, path={}, exception={}",
                errorCode.getCode(), status.value(), request.getMethod(), path, exception.getClass().getName());
        } else if (status.is5xxServerError()) {
            // 응답에는 싣지 않는 원인 사슬의 타입 · 스택을 여기서만 남긴다. 메시지는 버린다 — DB 행 값 · 대상 응답 본문이 들어 있을 수 있다 (#280)
            log.error("unhandled exception answered by fallback envelope. code={}, status={}, method={}, path={}",
                errorCode.getCode(), status.value(), request.getMethod(), path, RedactedThrowable.of(exception));
        } else if (status.value() == 401 || status.value() == 403) {
            // 인증 · 인가 오류는 SECURITY_00x 로 통일한다 (security-guide.md §4). 여기로 오면 COMMON_004 로 나가 클라이언트의 재인증 분기가 어긋난다 —
            // ResponseStatusException(401/403) 대신 보안 예외를 던지라는 신호다
            log.warn("auth status answered by fallback envelope instead of SECURITY code. status={}, method={}, path={}, exception={}",
                status.value(), request.getMethod(), path, exception.getClass().getName());
        } else {
            log.debug("framework error answered by fallback envelope. code={}, status={}, method={}, path={}, exception={}",
                errorCode.getCode(), status.value(), request.getMethod(), path, exception.getClass().getName());
        }
    }

    /** 봉투를 쓰고 응답을 커밋한다. MVC 밖 오류를 쓰는 {@link FallbackErrorController} 도 모양을 맞추려고 이 메서드를 쓴다. */
    static void write(JsonMapper jsonMapper, HttpServletResponse response, HttpStatusCode status, HttpHeaders headers, CommonErrorCode errorCode)
        throws IOException {
        byte[] body = jsonMapper.writeValueAsBytes(Response.fail(errorCode.getCode(), errorCode.getMessage()));
        response.setStatus(status.value());
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /** {@link LogPathMask} 로 가린다. MVC 밖 오류를 쓰는 {@link FallbackErrorController} 도 이 메서드를 쓴다. */
    static String maskPath(String path) {
        return LogPathMask.mask(path);
    }
}
