package com.commercefoundry.common.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.commercefoundry.common.exception.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서블릿 서비스의 폴백 오류 봉투 스모크. 도메인 · 보안 advice 가 받지 않는 404 · 405 · 415 · 400 · 500 이 Boot 기본 오류 JSON 이 아니라
 * {@code Response.fail} 봉투({@code COMMON_00x})로 나가는지, {@code @Order} 없는 advice 가 여전히 먼저 받는지 본다
 * ({@code api-design-guide.md} §2-1 공통 오류 코드). 서비스는 한 줄로 상속한다.
 *
 * <pre>{@code
 * class ProductServiceFallbackErrorSmokeTest extends FallbackErrorSmokeTestSupport {
 * }
 * }</pre>
 *
 * <ul>
 *   <li>애노테이션은 서비스의 {@code *ApplicationTests} 와 <b>정확히 같다</b> — 컨텍스트 캐시를 같이 써 서비스 컨텍스트를 한 번만 띄운다.
 *       서비스 쪽 애노테이션을 바꾸면 여기도 바꾼다.</li>
 *   <li>서비스 컨트롤러에 기대지 않는다 — {@link FallbackErrorSmokeController}({@link FallbackErrorSmokeContextCustomizerFactory} 가 등록)로 오류를 만든다.
 *       실제 서비스 구성(자동 구성 · 보안 체인 · {@code add-mappings: false})을 그대로 탄다.</li>
 *   <li>보안 오류(SECURITY_00x)가 바뀌지 않았는지는 security-core 의 {@code ResourceServerSmokeTestSupport} 가 같은 컨텍스트에서 본다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class FallbackErrorSmokeTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("매핑 없는 경로는 404 · COMMON_001 봉투다")
    void unmappedPath() throws Exception {
        assertEnvelope(perform(get(FallbackErrorSmokeController.PATH + "/missing")), CommonErrorCode.PATH_NOT_FOUND, 404);
    }

    @Test
    @DisplayName("매핑 없는 /api 경로도 404 · COMMON_001 봉투다 — 공개 경로는 URL 규칙을 통과해 MVC 까지 온다")
    void unmappedApiPath() throws Exception {
        assertEnvelope(perform(get("/api/v1/fallback-smoke-missing")), CommonErrorCode.PATH_NOT_FOUND, 404);
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 405 · COMMON_002 봉투이고 Allow 헤더를 유지한다")
    void methodNotAllowed() throws Exception {
        MockHttpServletResponse response = perform(delete(FallbackErrorSmokeController.PATH));

        assertEnvelope(response, CommonErrorCode.METHOD_NOT_ALLOWED, 405);
        assertThat(response.getHeader(HttpHeaders.ALLOW)).as(HttpHeaders.ALLOW).contains("GET");
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type 은 415 · COMMON_003 봉투다")
    void unsupportedMediaType() throws Exception {
        MockHttpServletResponse response = perform(post(FallbackErrorSmokeController.JSON_PATH).contentType(MediaType.TEXT_PLAIN).content("x"));

        assertEnvelope(response, CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, 415);
    }

    @Test
    @DisplayName("도메인 advice 가 받지 않는 바인딩 오류(필수 헤더 누락)는 400 · COMMON_004 봉투다")
    void bindingError() throws Exception {
        assertEnvelope(perform(get(FallbackErrorSmokeController.HEADER_PATH)), CommonErrorCode.BAD_REQUEST, 400);
    }

    @Test
    @DisplayName("처리되지 않은 예외는 500 · COMMON_000 봉투이고 원인 메시지를 응답에 싣지 않는다")
    void unhandledException() throws Exception {
        MockHttpServletResponse response = perform(get(FallbackErrorSmokeController.FAILURE_PATH));

        assertEnvelope(response, CommonErrorCode.INTERNAL_ERROR, 500);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).as("본문").doesNotContain(FallbackErrorSmokeController.FAILURE_DETAIL)
            .doesNotContain("IllegalStateException").doesNotContain("trace");
    }

    @Test
    @DisplayName("@Order 없는 advice 가 받는 예외는 폴백이 아니라 그 advice 의 응답이다")
    void adviceStillWins() throws Exception {
        assertEnvelope(perform(get(FallbackErrorSmokeController.HANDLED_PATH)), 409,
            FallbackErrorSmokeController.HandledAdvice.CODE, FallbackErrorSmokeController.HandledAdvice.MESSAGE);
    }

    protected MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    protected static void assertEnvelope(MockHttpServletResponse response, CommonErrorCode errorCode, int status) throws Exception {
        assertEnvelope(response, status, errorCode.getCode(), errorCode.getMessage());
    }

    /** JSON 트리로 타입 계약까지 본다 — {@code success} 는 boolean {@code false}, {@code resultMessage} 는 문자열, {@code fieldErrors} · {@code dataBody} 는 {@code null}. */
    protected static void assertEnvelope(MockHttpServletResponse response, int status, String resultCode, String resultMessage) throws Exception {
        assertThat(response.getStatus()).as("status").isEqualTo(status);
        assertThat(response.getContentType()).as("content type").startsWith(MediaType.APPLICATION_JSON_VALUE);

        JsonNode root = JSON.readTree(response.getContentAsByteArray());
        JsonNode header = root.path("dataHeader");
        assertThat(header.path("success").isBoolean()).as("dataHeader.success 는 boolean 이다").isTrue();
        assertThat(header.path("success").asBoolean()).as("dataHeader.success").isFalse();
        assertThat(header.path("resultCode").asString()).as("dataHeader.resultCode").isEqualTo(resultCode);
        assertThat(header.path("resultMessage").asString()).as("dataHeader.resultMessage").isEqualTo(resultMessage);
        assertThat(header.path("fieldErrors").isNull()).as("dataHeader.fieldErrors 는 null 이다(키는 있다)").isTrue();
        assertThat(root.path("dataBody").isNull()).as("dataBody 는 null 이다(키는 있다)").isTrue();
    }
}
