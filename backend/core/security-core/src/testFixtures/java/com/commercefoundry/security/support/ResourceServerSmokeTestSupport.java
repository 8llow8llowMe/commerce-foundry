package com.commercefoundry.security.support;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertWwwAuthenticate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 서블릿 서비스의 인가 스모크. 서비스가 {@code spring-boot-starter-security} 와 {@code ResourceServerSecurityConfigurer} import 를
 * 빠뜨려 인증 없이 뜨는 구성 실패를 잡는다 ({@code security-guide.md} §5-3). 서비스는 한 줄로 상속한다.
 *
 * <pre>{@code
 * class ProductServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {
 * }
 * }</pre>
 *
 * <ul>
 *   <li>애노테이션은 서비스의 {@code *ApplicationTests} 와 <b>정확히 같다</b> — 컨텍스트 캐시를 같이 써 서비스 컨텍스트를 한 번만 띄운다.
 *       서비스 쪽 애노테이션을 바꾸면 여기도 바꾼다.</li>
 *   <li>서비스 컨트롤러에 기대지 않는다 — 어느 서비스에도 없는 경로({@value #ADMIN_PATH} · {@value #PUBLIC_PATH})로 URL 규칙과 토큰 필터만 본다.
 *       규칙을 통과하면 매핑이 없어 404 다.</li>
 *   <li>{@code @PreAuthorize("isAuthenticated()")} 의 익명 401 은 첫 실제 핸들러가 생기는 이슈에서 그 경로로 서비스 테스트에 더한다.
 *       004 · 006 은 디코더 장애를 흉내 내야 해 security-core 통합 테스트에만 둔다. {@code /internal/**} 은 여기서 단언하지 않는다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class ResourceServerSmokeTestSupport {

    /** {@code /api/v1/admin/**} 규칙에 걸리는, 어느 서비스에도 매핑되지 않는 경로. */
    protected static final String ADMIN_PATH = "/api/v1/admin/smoke";
    /** 어드민이 아닌, 어느 서비스에도 매핑되지 않는 경로. */
    protected static final String PUBLIC_PATH = "/api/v1/smoke";

    @Autowired
    private MockMvc mockMvc;

    /**
     * 정규화되면 어드민 경로가 되는 원문 경로 — {@code ..} · {@code %2e} · {@code ;} · {@code //} · {@code %2f}.
     * security-core 의 {@code ResourceServerSecurityIntegrationTest} 도 이 목록을 쓴다.
     */
    public static Stream<String> nonNormalizedAdminPaths() {
        return Stream.of(
            "/api/v1/orders/../admin/x",
            "/api/v1/orders/%2e%2e/admin/x",
            "/api/v1/orders/%2E%2E/admin/x",
            "/api/v1/admin;x=y/x",
            "/api/v1/orders/..;/admin/x",
            "/api/v1//admin/x",
            "/api/v1/admin%2fx"
        );
    }

    @Test
    @DisplayName("토큰 없이 어드민 경로에 오면 401 · SECURITY_001 봉투 · WWW-Authenticate: Bearer")
    void anonymousOnAdminPath() throws Exception {
        MockHttpServletResponse response = perform(get(ADMIN_PATH));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
    }

    @Test
    @DisplayName("CUSTOMER 토큰으로 어드민 경로에 오면 403 · SECURITY_005 봉투 · WWW-Authenticate 없음")
    void customerOnAdminPath() throws Exception {
        MockHttpServletResponse response = perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));

        assertEnvelope(response, SecurityErrorCode.FORBIDDEN);
        assertWwwAuthenticate(response, null);
    }

    @Test
    @DisplayName("ADMIN 토큰은 어드민 URL 규칙을 통과한다 — 매핑이 없어 404")
    void adminPassesAdminRule() throws Exception {
        assertThat(perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getStatus()).isEqualTo(404);
    }

    /** 문자열 템플릿은 {@code %} 를 다시 인코딩하므로 {@link URI} 로 보낸다. */
    @Test
    @DisplayName("비예약 문자를 인코딩한 어드민 경로(/api/v1/%61dmin/smoke)도 규칙에 걸린다 — 무토큰 401 · CUSTOMER 403")
    void encodedAdminPathMatchesAdminRule() throws Exception {
        URI encoded = URI.create("/api/v1/%61dmin/smoke");

        assertEnvelope(perform(get(encoded)), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get(encoded).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
    }

    @ParameterizedTest
    @MethodSource("nonNormalizedAdminPaths")
    @DisplayName("정규화되면 어드민 경로가 되는 원문 경로는 무토큰 · CUSTOMER 모두 방화벽이 400 으로 끊는다 — 봉투 없음")
    void nonNormalizedPathIsRejectedByFirewall(String path) throws Exception {
        MockHttpServletResponse anonymous = perform(get(URI.create(path)));
        MockHttpServletResponse customer = perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));

        assertThat(anonymous.getStatus()).as(path).isEqualTo(400);
        assertThat(anonymous.getContentAsByteArray()).as(path).isEmpty();
        assertThat(customer.getStatus()).as(path).isEqualTo(400);
        assertThat(customer.getContentAsByteArray()).as(path).isEmpty();
    }

    @Test
    @DisplayName("대소문자가 다른 경로(/API/v1/admin/x)는 어디에도 매핑되지 않아 404 다")
    void upperCasePathIsNotFound() throws Exception {
        assertThat(perform(get("/API/v1/admin/x")).getStatus()).isEqualTo(404);
        assertThat(perform(get("/API/v1/admin/x").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("어드민이 아닌 경로에도 틀린 토큰을 보내면 401 · SECURITY_003 이다 — 익명으로 넘어가지 않는다")
    void invalidTokenOnPublicPath() throws Exception {
        MockHttpServletResponse response = perform(get(PUBLIC_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"));

        assertEnvelope(response, SecurityErrorCode.TOKEN_INVALID);
        assertWwwAuthenticate(response, "Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("만료된 토큰은 401 · SECURITY_002 다")
    void expiredToken() throws Exception {
        String expired = TestTokens.issuer(Instant.now().minus(Duration.ofHours(2))).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

        MockHttpServletResponse response = perform(get(PUBLIC_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));

        assertEnvelope(response, SecurityErrorCode.TOKEN_EXPIRED);
        assertWwwAuthenticate(response, "Bearer error=\"invalid_token\", error_description=\"The access token expired\"");
    }

    @Test
    @DisplayName("인증된 요청도 세션을 만들지 않는다 (STATELESS) — Set-Cookie 없음")
    void createsNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    // ── 도우미 ────────────────────────────────────────────────────

    protected final MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** 발급기({@link TestTokens#issuer})로 지금 발급한 access 토큰. 서명 키는 컨텍스트의 공개키와 한 쌍이다. */
    protected static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
