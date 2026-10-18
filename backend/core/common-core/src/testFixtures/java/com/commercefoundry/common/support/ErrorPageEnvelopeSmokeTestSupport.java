package com.commercefoundry.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.exception.CommonErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서블릿 서비스의 <b>MVC 밖</b> 오류 봉투 스모크. 실제 서블릿 컨테이너(RANDOM_PORT)에서 방화벽 거부(400)가 {@code /error} 에러 디스패치를 거쳐
 * Boot 기본 오류 JSON 이 아니라 {@code Response.fail} 봉투({@code COMMON_004})로 나가는지 본다 ({@code api-design-guide.md} §2-1 공통 오류 코드).
 * 서비스는 토큰 한 줄만 채워 상속한다.
 *
 * <pre>{@code
 * class ProductServiceErrorPageEnvelopeSmokeTest extends ErrorPageEnvelopeSmokeTestSupport {
 *
 *     @Override
 *     protected String customerAuthorization() {
 *         return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><b>MockMvc 로는 볼 수 없다.</b> MockMvc 는 {@code sendError} 뒤 에러 디스패치를 하지 않는다 — security-core 의
 *       {@code ResourceServerSmokeTestSupport#nonNormalizedPathIsRejectedByFirewall} 가 400 · <b>빈 본문</b>을 보는 이유다. 봉투는 컨테이너에서만 나온다.</li>
 *   <li><b>RANDOM_PORT 라 MockMvc 스모크({@code *ApplicationTests} · {@code *SecuritySmokeTest} · {@code *FallbackErrorSmokeTest})와 컨텍스트를 같이 쓰지 못한다.</b>
 *       서비스당 컨텍스트가 하나 더 뜬다. 이 클래스를 상속하는 테스트는 서비스마다 하나만 둔다.</li>
 *   <li>보안 체인을 그대로 탄다 — 에러 디스패치({@code /error})가 토큰 없음 · 고객 토큰 모두 사용자 체인을 통과해야 봉투가 나간다.</li>
 *   <li>고객 토큰은 security-core 픽스처로 만든다. 이 모듈은 security-core 를 모르므로 서비스가 {@link #customerAuthorization()} 으로 넘긴다.</li>
 *   <li>원문 경로를 그대로 보내야 해 JDK {@link HttpClient} 를 쓴다 — {@link URI} 의 원문 경로({@code getRawPath})를 정규화 · 재인코딩하지 않고 요청 줄에 싣는다.
 *       정규화됐다면 무토큰은 401, 고객은 403 이 나와 이 스모크가 실패한다.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ErrorPageEnvelopeSmokeTestSupport {

    /** security-core {@code SecurityErrorCode.UNAUTHORIZED} 의 코드. 이 모듈은 security-core 에 의존하지 않아 문자열로 적는다. */
    protected static final String SECURITY_UNAUTHORIZED_CODE = "SECURITY_001";
    /** {@code /api/v1/admin/**} 규칙에 걸리는, 어느 서비스에도 매핑되지 않는 경로. */
    protected static final String ADMIN_PATH = "/api/v1/admin/error-page-smoke";
    /** 어드민이 아닌, 어느 서비스에도 매핑되지 않는 경로. */
    protected static final String UNMAPPED_PATH = "/api/v1/__no-such";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    private int port;

    /** 고객({@code CUSTOMER}) access 토큰을 실은 {@code Authorization} 헤더 값 — {@code "Bearer ..."}. */
    protected abstract String customerAuthorization();

    /**
     * Spring Security 방화벽({@code StrictHttpFirewall})이 거부하는 원문 경로 — security-core {@code ResourceServerSmokeTestSupport#nonNormalizedAdminPaths} 에서
     * Tomcat 이 먼저 끊는 것({@link #containerRejectedPaths()})을 뺀 나머지다. 둘을 합치면 그 목록과 같다.
     */
    public static Stream<String> firewallRejectedPaths() {
        return Stream.of(
            "/api/v1/orders/../admin/x",
            "/api/v1/orders/%2e%2e/admin/x",
            "/api/v1/orders/%2E%2E/admin/x",
            "/api/v1/admin;x=y/x",
            "/api/v1/orders/..;/admin/x",
            "/api/v1//admin/x"
        );
    }

    /**
     * Spring 에 닿기 전에 Tomcat 이 400 으로 끊는 원문 경로. 인코딩된 슬래시({@code %2f})는 Tomcat 기본값({@code encodedSolidusHandling=reject})이 거부한다.
     * 애플리케이션 필터 · 에러 페이지를 거치지 않아 봉투가 아니다 — Tomcat {@code ErrorReportValve} 의 HTML 400 이다. 서비스 응답(봉투 · Boot 기본 JSON)이 아니고
     * 서버 이름 · 버전을 싣지 않는지만 본다. 운영에서는 게이트웨이가 앞에 서므로 클라이언트가 이 HTML 을 직접 받지 않는다.
     */
    public static Stream<String> containerRejectedPaths() {
        return Stream.of("/api/v1/admin%2fx");
    }

    @ParameterizedTest
    @MethodSource("firewallRejectedPaths")
    @DisplayName("방화벽이 거부한 원문 경로는 무토큰 · 고객 토큰 모두 400 · COMMON_004 봉투다 — /error 에러 디스패치가 보안 체인을 통과한다")
    void firewallRejectionIsEnvelope(String path) throws Exception {
        assertEnvelope(send(path, null, null), path, 400, CommonErrorCode.BAD_REQUEST.getCode(), CommonErrorCode.BAD_REQUEST.getMessage());
        assertEnvelope(send(path, customerAuthorization(), null), path, 400, CommonErrorCode.BAD_REQUEST.getCode(), CommonErrorCode.BAD_REQUEST.getMessage());
    }

    @Test
    @DisplayName("Accept: text/html 이어도 HTML 화이트라벨이 아니라 JSON 봉투다")
    void firewallRejectionIsJsonForHtmlAccept() throws Exception {
        String path = firewallRejectedPaths().findFirst().orElseThrow();

        assertEnvelope(send(path, null, MediaType.TEXT_HTML_VALUE), path, 400, CommonErrorCode.BAD_REQUEST.getCode(), CommonErrorCode.BAD_REQUEST.getMessage());
    }

    @ParameterizedTest
    @MethodSource("containerRejectedPaths")
    @DisplayName("Tomcat 이 먼저 끊는 경로는 400 이고 서비스 응답이 아니다 — 애플리케이션에 닿지 않는다")
    void containerRejectionNeverReachesService(String path) throws Exception {
        HttpResponse<byte[]> response = send(path, customerAuthorization(), null);

        assertThat(response.statusCode()).as(path).isEqualTo(400);
        String body = new String(response.body());
        assertThat(body).as(path).doesNotContain("dataHeader").doesNotContain("\"timestamp\"").doesNotContain("Apache Tomcat");
    }

    // ── 회귀 — 같은 실제 컨테이너에서 기존 응답이 바뀌지 않았는지 ──────────────

    @Test
    @DisplayName("토큰 없이 어드민 경로에 오면 여전히 401 · SECURITY_001 봉투다 — 보안 진입점이 직접 쓰고 /error 로 가지 않는다")
    void anonymousOnAdminPath() throws Exception {
        HttpResponse<byte[]> response = send(ADMIN_PATH, null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(response.body()).path("dataHeader").path("resultCode").asString()).isEqualTo(SECURITY_UNAUTHORIZED_CODE);
    }

    @Test
    @DisplayName("매핑 없는 경로는 컨테이너에서도 404 · COMMON_001 봉투다")
    void unmappedPath() throws Exception {
        assertEnvelope(send(UNMAPPED_PATH, null, null), UNMAPPED_PATH, 404,
            CommonErrorCode.PATH_NOT_FOUND.getCode(), CommonErrorCode.PATH_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("직접 GET /error 는 무토큰 · 고객 토큰 모두 404 · COMMON_001 봉투다 — 매핑 없는 경로처럼 보인다")
    void directErrorPath() throws Exception {
        assertEnvelope(send("/error", null, null), "/error", 404, CommonErrorCode.PATH_NOT_FOUND.getCode(), CommonErrorCode.PATH_NOT_FOUND.getMessage());
        assertEnvelope(send("/error", customerAuthorization(), null), "/error", 404,
            CommonErrorCode.PATH_NOT_FOUND.getCode(), CommonErrorCode.PATH_NOT_FOUND.getMessage());
    }

    /** {@code rawPath} 를 그대로 요청 줄에 싣는다. {@code authorization} · {@code accept} 는 {@code null} 이면 보내지 않는다. */
    protected final HttpResponse<byte[]> send(String rawPath, String authorization, String accept) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath)).timeout(Duration.ofSeconds(10)).GET();
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (accept != null) {
            request.header(HttpHeaders.ACCEPT, accept);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** {@code FallbackErrorSmokeTestSupport#assertEnvelope} 와 같은 계약 — 그쪽은 MockMvc 응답, 여기는 실제 HTTP 응답이다. Boot 기본 오류 JSON 이 아님도 본다. */
    protected static void assertEnvelope(HttpResponse<byte[]> response, String path, int status, String resultCode, String resultMessage) {
        assertThat(response.statusCode()).as("%s status", path).isEqualTo(status);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE)).as("%s content type", path)
            .hasValueSatisfying(contentType -> assertThat(contentType).startsWith(MediaType.APPLICATION_JSON_VALUE));

        JsonNode root = JSON.readTree(response.body());
        assertThat(root.has("timestamp")).as("%s Boot 기본 오류 JSON 이 아니다", path).isFalse();
        JsonNode header = root.path("dataHeader");
        assertThat(header.path("success").isBoolean()).as("%s dataHeader.success 는 boolean 이다", path).isTrue();
        assertThat(header.path("success").asBoolean()).as("%s dataHeader.success", path).isFalse();
        assertThat(header.path("resultCode").asString()).as("%s dataHeader.resultCode", path).isEqualTo(resultCode);
        assertThat(header.path("resultMessage").asString()).as("%s dataHeader.resultMessage", path).isEqualTo(resultMessage);
        assertThat(header.path("fieldErrors").isNull()).as("%s dataHeader.fieldErrors 는 null 이다(키는 있다)", path).isTrue();
        assertThat(root.path("dataBody").isNull()).as("%s dataBody 는 null 이다(키는 있다)", path).isTrue();
    }
}
