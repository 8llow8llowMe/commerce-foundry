package com.commercefoundry.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.exception.CommonErrorCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class FallbackErrorControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 방화벽이 거부하는 모양의 원문 경로. 넷째 구간부터 로그에 남으면 안 된다. */
    private static final String REJECTED_PATH = "/api/v1/orders/..;/admin/4611686018427387904";

    private final FallbackErrorController controller = new FallbackErrorController(JSON, new DefaultErrorAttributes());

    @ParameterizedTest
    @CsvSource({
        "400, COMMON_004",
        "401, COMMON_004",
        "403, COMMON_004",
        "404, COMMON_001",
        "405, COMMON_002",
        "415, COMMON_003",
        "429, COMMON_004",
        "500, COMMON_000",
        "503, COMMON_000"
    })
    @DisplayName("에러 디스패치의 상태를 그대로 쓰고 코드는 폴백 리졸버와 같은 표다 — 401 · 403 도 COMMON_004")
    void mapsDispatchedStatus(int status, String expectedCode) throws Exception {
        MockHttpServletResponse response = handle(errorDispatch(status));

        assertEnvelope(response, status, expectedCode);
    }

    @Test
    @DisplayName("에러 디스패치가 아닌 직접 요청(GET /error)은 매핑 없는 경로처럼 404 · COMMON_001 이다")
    void directRequestIsNotFound() throws Exception {
        assertEnvelope(handle(new MockHttpServletRequest(HttpMethod.GET.name(), "/error")), 404, "COMMON_001");
    }

    @ParameterizedTest
    @CsvSource({"200", "302", "99", "600"})
    @DisplayName("4xx · 5xx 가 아닌 상태로 불리면 서버 결함으로 보고 500 · COMMON_000 이다")
    void nonErrorStatusIsInternalError(int status) throws Exception {
        assertEnvelope(handle(errorDispatch(status)), 500, "COMMON_000");
    }

    @Test
    @DisplayName("상태 속성이 정수가 아니면 직접 요청과 같이 404 · COMMON_001 이다")
    void nonIntegerStatusIsNotFound() throws Exception {
        MockHttpServletRequest request = errorDispatch(400);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, "400");

        assertEnvelope(handle(request), 404, "COMMON_001");
    }

    @Test
    @DisplayName("필터 예외의 500 은 원인 메시지 · 예외 타입을 응답에 싣지 않고 로그에만 남긴다")
    void causeStaysInLog(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = errorDispatch(500);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("password=secret"));

        MockHttpServletResponse response = handle(request);

        assertEnvelope(response, 500, "COMMON_000");
        assertThat(response.getContentAsString()).doesNotContain("password=secret").doesNotContain("IllegalStateException").doesNotContain("trace");
        assertThat(output).contains("unhandled exception answered by error page envelope").contains("password=secret");
    }

    @Test
    @DisplayName("로그 경로는 원래 요청 경로(ERROR_REQUEST_URI)의 앞 3구간만 남긴다 — 방화벽이 거부한 원문 경로를 그대로 남기지 않는다")
    void logsMaskedOriginalPath(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = errorDispatch(503);

        handle(request);

        assertThat(output).contains("path=/api/v1/orders/…").doesNotContain("4611686018427387904").doesNotContain("..;");
    }

    @Test
    @DisplayName("Accept: text/html 이어도 HTML 화이트라벨이 아니라 JSON 봉투다")
    void alwaysJson() throws Exception {
        MockHttpServletRequest request = errorDispatch(400);
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE);

        assertEnvelope(handle(request), 400, "COMMON_004");
    }

    @Test
    @DisplayName("이미 커밋된 응답에는 쓰지 않는다")
    void committedResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write('x');
        response.flushBuffer();

        controller.error(errorDispatch(500), response);

        assertThat(response.getContentAsString()).isEqualTo("x");
    }

    private MockHttpServletResponse handle(MockHttpServletRequest request) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);
        return response;
    }

    /** 컨테이너가 에러 페이지로 넘길 때와 같은 모양 — ERROR 디스패치, {@code /error} 경로, 원래 경로 · 상태 속성. */
    private static MockHttpServletRequest errorDispatch(int status) {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, REJECTED_PATH);
        return request;
    }

    private static void assertEnvelope(MockHttpServletResponse response, int status, String code) throws Exception {
        CommonErrorCode errorCode = CommonErrorCode.valueOf(codeName(code));
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode root = JSON.readTree(response.getContentAsByteArray());
        JsonNode header = root.path("dataHeader");
        assertThat(header.path("success").asBoolean(true)).isFalse();
        assertThat(header.path("resultCode").asString()).isEqualTo(code);
        assertThat(header.path("resultMessage").asString()).isEqualTo(errorCode.getMessage());
        assertThat(header.path("fieldErrors").isNull()).isTrue();
        assertThat(root.path("dataBody").isNull()).isTrue();
        assertThat(root.has("timestamp")).as("Boot 기본 오류 JSON 이 아니다").isFalse();
    }

    private static String codeName(String code) {
        for (CommonErrorCode candidate : CommonErrorCode.values()) {
            if (candidate.getCode().equals(code)) {
                return candidate.name();
            }
        }
        throw new IllegalArgumentException(code);
    }
}
