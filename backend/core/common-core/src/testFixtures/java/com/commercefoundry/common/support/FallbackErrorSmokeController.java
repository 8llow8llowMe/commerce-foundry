package com.commercefoundry.common.support;

import com.commercefoundry.common.dto.Response;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 폴백 오류 봉투 스모크({@link FallbackErrorSmokeTestSupport}) 전용 핸들러. {@link FallbackErrorSmokeContextCustomizerFactory} 가 서블릿 테스트 컨텍스트에 등록한다.
 *
 * <p>경로는 {@code /api/**} · {@code /internal/**} 밖이다 — 사용자 체인의 {@code permitAll} 로 열려 있고, 인가 선언 가드
 * ({@code ResourceServerSmokeTestSupport#handlersDeclareAuthorization})의 검사 대상도 아니다. 서비스 컨트롤러에 기대지 않고
 * 405 · 415 · 400 · 500 을 만들기 위한 것이다.
 */
@RestController
@RequestMapping(FallbackErrorSmokeController.PATH)
public class FallbackErrorSmokeController {

    public static final String PATH = "/fallback-smoke";
    public static final String JSON_PATH = PATH + "/json";
    public static final String HEADER_PATH = PATH + "/header";
    public static final String FAILURE_PATH = PATH + "/failure";
    public static final String HANDLED_PATH = PATH + "/handled";
    public static final String REQUIRED_HEADER = "X-Fallback-Smoke";
    /** 500 응답 · 로그 분리를 보려고 예외 메시지에 넣는 값. 응답 본문에 나오면 안 된다. */
    public static final String FAILURE_DETAIL = "jdbc:postgresql://db.internal:5432/orders password=fallback-smoke";

    /** GET 만 연다 — 다른 메서드는 405 다. */
    @GetMapping
    public Response<Void> get() {
        return Response.success();
    }

    /** JSON 만 받는다 — 다른 Content-Type 은 415 다. */
    @PostMapping(path = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response<Void> json(@RequestBody Map<String, Object> body) {
        return Response.success();
    }

    /** 필수 헤더 누락은 도메인 advice 가 받지 않는 바인딩 400 이다. */
    @GetMapping("/header")
    public Response<Void> header(@RequestHeader(REQUIRED_HEADER) String value) {
        return Response.success();
    }

    /** 어느 advice 도 받지 않는 예외 — DB 장애 같은 처리되지 않은 오류를 흉내 낸다. */
    @GetMapping("/failure")
    public Response<Void> failure() {
        throw new IllegalStateException(FAILURE_DETAIL);
    }

    /** {@link HandledAdvice} 가 받는 예외 — {@code @Order} 없는 advice 도 폴백보다 먼저 받는지 본다. */
    @GetMapping("/handled")
    public Response<Void> handled() {
        throw new HandledException();
    }

    static final class HandledException extends RuntimeException {
    }

    /** 도메인 {@code *ExceptionHandler} 처럼 {@code @Order} 를 달지 않는다. 스모크 핸들러에만 걸린다. */
    @RestControllerAdvice(assignableTypes = FallbackErrorSmokeController.class)
    public static class HandledAdvice {

        public static final String CODE = "SMOKE_001";
        public static final String MESSAGE = "스모크 advice 가 받았습니다.";

        @ExceptionHandler(HandledException.class)
        public ResponseEntity<Response<Void>> handle(HandledException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Response.fail(CODE, MESSAGE));
        }
    }
}
