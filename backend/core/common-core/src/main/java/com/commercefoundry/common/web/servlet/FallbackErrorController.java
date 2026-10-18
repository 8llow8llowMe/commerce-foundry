package com.commercefoundry.common.web.servlet;

import com.commercefoundry.common.exception.CommonErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVC 밖에서 난 오류 — 서블릿 필터 예외, Spring Security 방화벽 거부(400), 그 밖의 {@code sendError} — 를 {@code Response.fail} 봉투로 쓴다
 * ({@code api-design-guide.md} §2-1 공통 오류 코드). Boot 의 {@code BasicErrorController} 를 대신한다.
 *
 * <p>MVC 안의 오류는 {@link FallbackErrorResolver} 가 이미 봉투로 끝낸다. 여기로 오는 것은 {@code DispatcherServlet} 에 닿기 전(필터)이거나
 * 컨테이너가 {@code sendError} 를 받은 경우다 — 컨테이너가 에러 페이지({@code /error})로 ERROR 디스패치를 하고, 없으면 Boot 기본 오류 JSON
 * ({@code timestamp} · {@code status} · {@code error} · {@code path})이 나가 BFF · 서비스 간 호출의 봉투 파서가 갈라진다.
 *
 * <ul>
 *   <li><b>상태</b>는 요청 속성 {@link RequestDispatcher#ERROR_STATUS_CODE}(Boot {@code ErrorAttributes} 와 같은 출처)이고, 코드는 #54 와 같은 표
 *       ({@link CommonErrorCode#of})다. 속성이 없으면 에러 디스패치가 아니라 {@code GET /error} 같은 직접 요청이다 — 매핑 없는 경로처럼 404 {@code COMMON_001}.
 *       4xx · 5xx 가 아닌 값은 서버 결함으로 보고 500 {@code COMMON_000} 이다.</li>
 *   <li><b>Accept 와 무관하게 언제나 JSON 봉투</b>다. HTML 화이트라벨을 내지 않는다 — 메시지 변환기를 거치지 않고 직접 써서 {@code Accept: text/html} 에도 406 이 되지 않는다.</li>
 *   <li>500 의 원인 · 스택은 응답에 싣지 않고 로그에만 남긴다. 로그 경로는 원래 요청 경로({@link RequestDispatcher#ERROR_REQUEST_URI})를
 *       {@link FallbackErrorResolver#maskPath} 로 가린다 — 방화벽이 거부한 경로에는 공격 문자열 · 식별자가 들어온다.</li>
 *   <li>{@code @PreAuthorize} 를 달지 않는다. 오류 응답은 원래 요청의 인증 여부와 무관하게 나가야 한다 — 메서드 보안이 ERROR 디스패치의 익명 요청을 거부하면
 *       401 이 400 · 500 을 덮는다. URL 규칙도 {@code /error} 를 막지 않는다(사용자 체인 {@code anyRequest().permitAll()}).</li>
 *   <li>Swagger 에 내지 않는다({@code @Hidden}) — API 가 아니다.</li>
 * </ul>
 *
 * <p>{@code @Controller} 이지만 컴포넌트 스캔 대상이 아니다(core 패키지). {@link FallbackErrorAutoConfiguration} 이 빈으로 등록한다.
 */
@Slf4j
@Hidden
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class FallbackErrorController implements ErrorController {

    private final JsonMapper jsonMapper;
    private final ErrorAttributes errorAttributes;

    /**
     * 서비스 컨텍스트의 {@code JsonMapper} 빈을 넘긴다 — {@link FallbackErrorResolver} 와 같은 설정이라 모양이 같다.
     * {@code errorAttributes} 는 원인을 찾는 데만 쓴다. 없으면({@code ErrorMvcAutoConfiguration} 제외) 서블릿 속성 {@link RequestDispatcher#ERROR_EXCEPTION} 만 본다.
     */
    public FallbackErrorController(JsonMapper jsonMapper, ErrorAttributes errorAttributes) {
        this.jsonMapper = jsonMapper;
        this.errorAttributes = errorAttributes;
    }

    /** 모든 메서드를 받는다 — 원래 요청이 POST · DELETE 여도 에러 디스패치는 같은 메서드로 온다. */
    @RequestMapping
    public void error(HttpServletRequest request, HttpServletResponse response) {
        String path = FallbackErrorResolver.maskPath(originalPath(request));
        if (response.isCommitted()) {
            log.warn("error page envelope skipped since response is committed. method={}, path={}", request.getMethod(), path);
            return;
        }

        Object rawStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatusCode status = statusOf(rawStatus);
        CommonErrorCode errorCode = CommonErrorCode.of(status);
        log(request, rawStatus, status, errorCode, path);
        try {
            FallbackErrorResolver.write(jsonMapper, response, status, HttpHeaders.EMPTY, errorCode);
        } catch (IOException writeFailure) {
            log.warn("error page envelope write failed. code={}, method={}, path={}", errorCode.getCode(), request.getMethod(), path, writeFailure);
        }
    }

    /**
     * 에러 디스패치면 컨테이너가 넣은 상태, 직접 요청이면 404. 4xx · 5xx 밖의 값은 500 이다 —
     * {@code sendError(200)} 처럼 오류가 아닌 상태로 오류 페이지가 불린 것은 호출한 쪽의 결함이다.
     */
    static HttpStatusCode statusOf(Object rawStatus) {
        if (!(rawStatus instanceof Integer code)) {
            return HttpStatus.NOT_FOUND;
        }
        if (code < 400 || code > 599) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatusCode.valueOf(code);
    }

    private void log(HttpServletRequest request, Object rawStatus, HttpStatusCode status, CommonErrorCode errorCode, String path) {
        if (!(rawStatus instanceof Integer code)) {
            // 직접 요청은 결함이 아니다. 스캐너가 두드려도 로그가 넘치지 않게 debug 로 둔다
            log.debug("direct request to error path answered as not found. method={}", request.getMethod());
            return;
        }
        Throwable cause = causeOf(request);
        if (status.value() != code) {
            log.error("error page reached with non-error status. code={}, rawStatus={}, method={}, path={}",
                errorCode.getCode(), rawStatus, request.getMethod(), path, cause);
        } else if (status.is5xxServerError() && cause != null) {
            // 필터 · 컨테이너 단계의 처리되지 않은 예외. 응답에는 싣지 않는 원인 · 스택을 여기서만 남긴다
            log.error("unhandled exception answered by error page envelope. code={}, status={}, method={}, path={}",
                errorCode.getCode(), status.value(), request.getMethod(), path, cause);
        } else if (status.is5xxServerError()) {
            // 예외 없이 sendError(5xx) 로 끝낸 경우 — 원인은 보낸 쪽이 남겼어야 한다
            log.warn("5xx without exception answered by error page envelope. code={}, status={}, method={}, path={}",
                errorCode.getCode(), status.value(), request.getMethod(), path);
        } else if (status.value() == 401 || status.value() == 403) {
            // 인증 · 인가 오류는 SECURITY_00x 로 통일한다 (security-guide.md §4). 보안 진입점 · 거부 처리기는 직접 봉투를 써서 여기로 오지 않는다 —
            // 오면 필터가 sendError(401/403) 로 끝낸 것이고, COMMON_004 로 나가 클라이언트의 재인증 분기가 어긋난다
            log.warn("auth status answered by error page envelope instead of SECURITY code. status={}, method={}, path={}, exception={}",
                status.value(), request.getMethod(), path, cause == null ? null : cause.getClass().getName());
        } else {
            // 방화벽 거부(400)가 대부분이다. 공격 스캐너가 반복해서 두드리므로 debug 로 둔다 — 경로는 가린 것만 남긴다
            log.debug("client error answered by error page envelope. code={}, status={}, method={}, path={}, exception={}",
                errorCode.getCode(), status.value(), request.getMethod(), path, cause == null ? null : cause.getClass().getName());
        }
    }

    /** Boot {@code ErrorAttributes} 가 기록한 예외, 없으면 컨테이너가 넣은 {@link RequestDispatcher#ERROR_EXCEPTION}. */
    private Throwable causeOf(HttpServletRequest request) {
        Throwable recorded = errorAttributes == null ? null : errorAttributes.getError(new ServletWebRequest(request));
        if (recorded != null) {
            return recorded;
        }
        return request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable throwable ? throwable : null;
    }

    /** 에러 디스패치의 {@code getRequestURI} 는 {@code /error} 다. 원래 경로는 속성에 있다. */
    private static String originalPath(HttpServletRequest request) {
        return request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original ? original : request.getRequestURI();
    }
}
