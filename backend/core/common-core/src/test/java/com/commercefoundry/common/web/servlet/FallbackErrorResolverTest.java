package com.commercefoundry.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.exception.CommonErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FallbackErrorResolverTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FallbackErrorResolver resolver = new FallbackErrorResolver(JSON);

    @Test
    @DisplayName("처리되지 않은 예외는 500 · COMMON_000 봉투이고 예외 메시지를 싣지 않는다")
    void unhandledException() throws Exception {
        MockHttpServletResponse response = resolve(new IllegalStateException("password=secret"));

        assertEnvelope(response, 500, CommonErrorCode.INTERNAL_ERROR);
        assertThat(response.getContentAsString()).doesNotContain("password=secret").doesNotContain("IllegalStateException");
    }

    @Test
    @DisplayName("405 는 프레임워크가 정한 Allow 헤더를 그대로 싣는다")
    void methodNotAllowedKeepsAllowHeader() throws Exception {
        MockHttpServletResponse response = resolve(new HttpRequestMethodNotSupportedException("DELETE", Set.of("GET", "POST")));

        assertEnvelope(response, 405, CommonErrorCode.METHOD_NOT_ALLOWED);
        assertThat(response.getHeader(HttpHeaders.ALLOW)).contains("GET").contains("POST");
    }

    @Test
    @DisplayName("ResponseStatusException 은 그 상태로 답한다 — 409 는 COMMON_004 다")
    void responseStatusException() throws Exception {
        assertEnvelope(resolve(new ResponseStatusException(HttpStatus.CONFLICT)), 409, CommonErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("@ResponseStatus 를 단 예외는 선언된 상태로 답한다 — Spring 기본 처리기보다 앞이라 여기서 상태를 읽는다")
    void annotatedException() throws Exception {
        assertEnvelope(resolve(new GoneException()), 410, CommonErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("@ResponseStatus 를 원인에만 단 예외도 그 상태로 답한다 — ResponseStatusExceptionResolver 와 같은 규칙")
    void annotatedCause() throws Exception {
        assertEnvelope(resolve(new IllegalStateException("wrapped", new GoneException())), 410, CommonErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("클라이언트가 끊은 비동기 요청은 쓰지 않고 다음 처리기로 넘긴다")
    void asyncRequestNotUsable() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView result = resolver.resolveException(request(), response, null, new AsyncRequestNotUsableException("closed"));

        assertThat(result).isNull();
        assertThat(response.getContentLength()).isZero();
    }

    @Test
    @DisplayName("이미 커밋된 응답에는 쓰지 않고 다음 처리기로 넘긴다")
    void committedResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write('x');
        response.flushBuffer();

        assertThat(resolver.resolveException(request(), response, null, new IllegalStateException())).isNull();
        assertThat(response.getContentAsString()).isEqualTo("x");
    }

    @Nested
    class StatusToCode {

        @ParameterizedTest
        @CsvSource({
            "404, COMMON_001",
            "405, COMMON_002",
            "415, COMMON_003",
            "400, COMMON_004",
            "406, COMMON_004",
            "409, COMMON_004",
            "500, COMMON_000",
            "503, COMMON_000"
        })
        @DisplayName("404 · 405 · 415 는 전용 코드, 그 밖의 4xx 는 COMMON_004, 5xx 는 COMMON_000 이다")
        void mapsStatus(int status, String expectedCode) {
            assertThat(CommonErrorCode.of(HttpStatusCode.valueOf(status)).getCode()).isEqualTo(expectedCode);
        }
    }

    @Nested
    class ResolverPosition {

        @Test
        @DisplayName("@ExceptionHandler 처리기 바로 뒤, Spring 기본 처리기 앞에 들어간다")
        void insertedRightAfterExceptionHandlerResolver() {
            List<HandlerExceptionResolver> resolvers = new ArrayList<>(List.of(
                new ExceptionHandlerExceptionResolver(), new ResponseStatusExceptionResolver(), new DefaultHandlerExceptionResolver()));

            new FallbackErrorAutoConfiguration().fallbackErrorWebMvcConfigurer(JSON).extendHandlerExceptionResolvers(resolvers);

            assertThat(resolvers).hasSize(4);
            assertThat(resolvers.get(0)).isInstanceOf(ExceptionHandlerExceptionResolver.class);
            assertThat(resolvers.get(1)).isInstanceOf(FallbackErrorResolver.class);
            assertThat(resolvers.get(2)).isInstanceOf(ResponseStatusExceptionResolver.class);
        }

        @Test
        @DisplayName("@ExceptionHandler 처리기가 없는 사슬이면 맨 앞에 들어간다")
        void insertedFirstWithoutExceptionHandlerResolver() {
            List<HandlerExceptionResolver> resolvers = new ArrayList<>(List.of(new DefaultHandlerExceptionResolver()));

            new FallbackErrorAutoConfiguration().fallbackErrorWebMvcConfigurer(JSON).extendHandlerExceptionResolvers(resolvers);

            assertThat(resolvers.getFirst()).isInstanceOf(FallbackErrorResolver.class);
        }
    }

    @Nested
    class PathMasking {

        @ParameterizedTest
        @CsvSource({
            "/api/v1/orders/4611686018427387904, /api/v1/orders/…",
            "/api/v1/orders/1/items/2, /api/v1/orders/…",
            "/api/v1/orders, /api/v1/orders",
            "/api/v1/orders/, /api/v1/orders/",
            "/fallback-smoke, /fallback-smoke"
        })
        @DisplayName("로그 경로는 앞 3구간만 남긴다 — 경로의 식별자를 남기지 않는다")
        void masksAfterThreeSegments(String path, String expected) {
            assertThat(FallbackErrorResolver.maskPath(path)).isEqualTo(expected);
        }
    }

    @ResponseStatus(HttpStatus.GONE)
    private static final class GoneException extends RuntimeException {
    }

    private MockHttpServletResponse resolve(Exception exception) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ModelAndView result = resolver.resolveException(request(), response, null, exception);
        assertThat(result).as("처리했으면 빈 ModelAndView 를 돌려준다 — 다음 처리기로 넘어가지 않는다").isNotNull();
        return response;
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest(HttpMethod.GET.name(), "/api/v1/orders/4611686018427387904");
    }

    private static void assertEnvelope(MockHttpServletResponse response, int status, CommonErrorCode errorCode) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode header = JSON.readTree(response.getContentAsByteArray()).path("dataHeader");
        assertThat(header.path("success").asBoolean(true)).isFalse();
        assertThat(header.path("resultCode").asString()).isEqualTo(errorCode.getCode());
        assertThat(header.path("resultMessage").asString()).isEqualTo(errorCode.getMessage());
        assertThat(header.path("fieldErrors").isNull()).isTrue();
    }
}
