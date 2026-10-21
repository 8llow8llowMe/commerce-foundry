package com.commercefoundry;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertWwwAuthenticate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 인가 스모크 — security-core Resource Server 가 이 서비스에 실제로 걸렸는지 본다 ({@code security-guide.md} §5-3).
 * 애노테이션은 {@link AuthServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 *
 * <p>{@code isAuthenticated()} 핸들러(내 정보 · 로그아웃)의 익명 401 과 공개 핸들러(회원가입 · 로그인 · 재발급)의 {@code permitAll()} 을 실제 빈 구성으로 본다.
 * 공개 핸들러는 본문 검증 400 · refresh 형식 오류 401 로 끝나 토큰 저장소(Redis)에 닿지 않는다. auth 는 발급기({@code JwtIssuerConfigurer})를 import 하는 유일한 서비스다.
 *
 * <p>어드민 회원 조회(#6)는 실제 컨트롤러 경로로 무토큰 · CUSTOMER · ADMIN 과 우회 경로를 본다. "컨트롤러에 닿지 않았다" 는 유스케이스가 남기는
 * 감사 로그({@code action=MEMBER_SEARCH})가 stdout 에 없는 것으로 확인한다. <b>이 판정은 감사 로그가 콘솔(stdout)로 나간다는 전제에 기댄다</b> —
 * 로깅 설정이 {@code admin.audit} 를 파일로만 보내거나 끄면 "없음" 단언이 거짓으로 통과한다. 그래서 ADMIN 200 케이스의 {@code contains} 단언을 함께 두어
 * 같은 테스트 안에서 그 로그가 실제로 잡힌다는 것을 확인한다(지우지 않는다).
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    private static final String ME = "/api/v1/members/me";
    private static final String SHIPPING_ADDRESSES = ME + "/shipping-addresses";
    private static final String VALID_SHIPPING_ADDRESS = """
        {"recipientName":"홍길동","recipientPhone":"01012345678","countryCode":"KR","postalCode":"06236","addressLine1":"서울특별시 강남구 테헤란로 123"}""";
    private static final String ADMIN_LOGIN = "/api/v1/auth/admin/login";
    private static final String ADMIN_MEMBERS = "/api/v1/admin/members";

    @Override
    protected boolean issuerExpected() {
        return true;
    }

    @Test
    @DisplayName("로그아웃은 토큰 없으면 401 · SECURITY_001 · WWW-Authenticate: Bearer 다 (isAuthenticated) — 올바른 본문이어도")
    void anonymousLogout() throws Exception {
        MockHttpServletResponse response = perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}"));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
    }

    @Test
    @DisplayName("로그인은 익명으로 열려 있다 — 인증 오류가 아니라 본문 검증 400 · AUTH_101 이다 (permitAll)")
    void loginIsPublic() throws Exception {
        MockHttpServletResponse response = perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.getContentAsString(StandardCharsets.UTF_8), "$.dataHeader.resultCode")).isEqualTo("AUTH_101");
    }

    @Test
    @DisplayName("재발급은 익명으로 열려 있다 — SECURITY_001 이 아니라 refresh 형식 오류 401 · AUTH_010 이다(저장소를 부르기 전) (permitAll)")
    void reissueIsPublic() throws Exception {
        MockHttpServletResponse response = perform(post("/api/v1/auth/token/reissue").contentType(MediaType.APPLICATION_JSON).content("{}"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(JsonPath.<String>read(response.getContentAsString(StandardCharsets.UTF_8), "$.dataHeader.resultCode")).isEqualTo("AUTH_010");
    }

    @Test
    @DisplayName("내 정보 조회는 토큰 없으면 401 · SECURITY_001 · WWW-Authenticate: Bearer 다 (isAuthenticated)")
    void anonymousGetMe() throws Exception {
        MockHttpServletResponse response = perform(get(ME));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
    }

    @Test
    @DisplayName("배송지 목록은 토큰 없으면 401 · SECURITY_001 · WWW-Authenticate: Bearer 다 (isAuthenticated)")
    void anonymousGetShippingAddresses() throws Exception {
        MockHttpServletResponse response = perform(get(SHIPPING_ADDRESSES));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
    }

    /** 쓰기 요청은 올바른 본문으로 보낸다 — 본문 검증(400)이 인가(401)보다 먼저다 ({@code api-design-guide.md} §5). */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "GET, /4611686018427387904",
        "POST, ''",
        "PUT, /4611686018427387904",
        "DELETE, /4611686018427387904",
        "PUT, /4611686018427387904/default"
    })
    @DisplayName("배송지 나머지 경로도 토큰 없으면 401 · SECURITY_001 이다 (isAuthenticated)")
    void anonymousShippingAddressWrites(String method, String suffix) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), SHIPPING_ADDRESSES + suffix);
        if (!"DELETE".equals(method) && !"GET".equals(method) && !suffix.endsWith("/default")) {
            request.contentType(MediaType.APPLICATION_JSON).content(VALID_SHIPPING_ADDRESS);
        }

        assertEnvelope(perform(request), SecurityErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("익명 배송지 추가에 빈 본문({})을 보내면 401 이 아니라 400 · AUTH_121 이다 — 본문 검증이 인가보다 먼저 돈다(리소스 데이터는 없다)")
    void anonymousCreateWithEmptyBodyIsValidatedFirst() throws Exception {
        MockHttpServletResponse response = perform(post(SHIPPING_ADDRESSES).contentType(MediaType.APPLICATION_JSON).content("{}"));
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(body, "$.dataHeader.resultCode")).isEqualTo("AUTH_121");
        assertThat(JsonPath.<Object>read(body, "$.dataBody")).isNull();
    }

    /**
     * 본문은 올바른 값으로 보낸다 — {@code @Valid @RequestBody} 는 인자를 만들 때 돌아 {@code @PreAuthorize}(메서드 호출 시점)보다 먼저다.
     * 익명이 틀린 본문을 보내면 400(AUTH_1xx)이 먼저 나온다 ({@code api-design-guide.md} §5).
     */
    @Test
    @DisplayName("내 정보 수정은 토큰 없으면 401 · SECURITY_001 이다 (isAuthenticated)")
    void anonymousPatchMe() throws Exception {
        MockHttpServletResponse response = perform(patch(ME).contentType(MediaType.APPLICATION_JSON).content("{\"marketingAgreed\":true}"));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("내 정보는 회원 토큰이면 인증 오류가 아니라 도메인 응답이다 — 픽스처 회원은 없으므로 404 · AUTH_003")
    void authenticatedReachesDomain() throws Exception {
        assertEnvelope(perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), 404, "AUTH_003", "존재하지 않는 회원입니다.");
    }

    @Test
    @DisplayName("회원가입은 익명으로 열려 있다 — 인증 오류가 아니라 본문 검증 400 · AUTH_101(첫 필드 email 누락)이다 (permitAll)")
    void signUpIsPublic() throws Exception {
        MockHttpServletResponse response = perform(post("/api/v1/auth/sign-up").contentType(MediaType.APPLICATION_JSON).content("{}"));
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(body, "$.dataHeader.resultCode")).isEqualTo("AUTH_101");
        assertThat(JsonPath.<String>read(body, "$.dataHeader.resultMessage")).isEqualTo("이메일은 필수입니다.");
        assertThat(JsonPath.<String>read(body, "$.dataHeader.fieldErrors[0].field")).isEqualTo("email");
    }

    // ── 관리자 (#6) ───────────────────────────────────────────────

    @Test
    @DisplayName("관리자 로그인은 /api/v1/admin 밖이라 익명으로 열려 있다 — 인증 오류가 아니라 본문 검증 400 · AUTH_130 이다 (permitAll)")
    void adminLoginIsPublic() throws Exception {
        MockHttpServletResponse response = perform(post(ADMIN_LOGIN).contentType(MediaType.APPLICATION_JSON).content("{}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.getContentAsString(StandardCharsets.UTF_8), "$.dataHeader.resultCode")).isEqualTo("AUTH_130");
    }

    @Test
    @DisplayName("어드민 회원 조회는 무토큰 401 · SECURITY_001, CUSTOMER 403 · SECURITY_005 이고 컨트롤러에 닿지 않는다(감사 로그 없음), ADMIN 은 200 이다")
    void adminMembersRequiresAdmin(CapturedOutput output) throws Exception {
        assertEnvelope(perform(get(ADMIN_MEMBERS)), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get(ADMIN_MEMBERS).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
        assertThat(output.getOut()).as("거부된 요청은 유스케이스(감사 로그)에 닿지 않는다").doesNotContain("action=MEMBER_SEARCH");

        MockHttpServletResponse admin = perform(get(ADMIN_MEMBERS).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)));
        assertThat(admin.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(admin.getContentAsString(StandardCharsets.UTF_8), "$.dataBody.page")).isZero();
        // 감사 로그가 stdout 에 잡힌다는 증거 — 위의 doesNotContain("미도달") 판정이 거짓 통과하지 않게 지우지 않는다
        assertThat(output.getOut()).contains("action=MEMBER_SEARCH");
    }

    /**
     * 정규화되면 {@code /api/v1/admin/members} 가 되는 원문 경로. 방화벽이 필터 체인 앞에서 끊어 URL 규칙 · 컨트롤러에 닿지 않는다.
     * 공통 경로(어느 서비스에도 없는 {@code /api/v1/admin/x})는 {@link ResourceServerSmokeTestSupport} 가 보고, 여기서는 실제 어드민 컨트롤러 경로로 본다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/members/../admin/members",
        "/api/v1/members/%2e%2e/admin/members",
        "/api/v1/members/%2E%2E/admin/members",
        "/api/v1/members/..;/admin/members",
        "/api/v1/admin;x=y/members",
        "/api/v1/admin/members;jsessionid=x",
        "/api/v1/members/%252e%252e/admin/members",
        "/api/v1/admin%2Fmembers",
        "/api/v1/members/..%2Fadmin/members"
    })
    @DisplayName("우회 경로(.. · %2e%2e · ; · 이중 인코딩 %252e · 대문자 %2F)는 무토큰 · CUSTOMER 모두 400 이고 어드민 회원 조회에 닿지 않는다")
    void bypassPathsDoNotReachAdminController(String path, CapturedOutput output) throws Exception {
        MockHttpServletResponse anonymous = perform(get(URI.create(path)));
        MockHttpServletResponse customer = perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));

        assertThat(anonymous.getStatus()).as(path).isEqualTo(400);
        assertThat(customer.getStatus()).as(path).isEqualTo(400);
        assertThat(output.getOut()).as(path).doesNotContain("action=MEMBER_SEARCH");
    }

    @Test
    @DisplayName("비예약 문자를 인코딩한 경로(/api/v1/%61dmin/members)는 어드민 규칙에 그대로 걸린다 — 무토큰 401 · CUSTOMER 403, 컨트롤러에 닿지 않는다")
    void encodedAdminMembersPath(CapturedOutput output) throws Exception {
        URI encoded = URI.create("/api/v1/%61dmin/members");

        assertEnvelope(perform(get(encoded)), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get(encoded).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
        assertThat(output.getOut()).doesNotContain("action=MEMBER_SEARCH");
    }

    /** MVC 는 끝 슬래시를 다른 경로로 본다(Spring 6 기본) — URL 규칙({@code /api/v1/admin/**})에는 걸리고, ADMIN 이어도 컨트롤러에 매핑되지 않는다. */
    @Test
    @DisplayName("끝 슬래시 경로(/api/v1/admin/members/)는 무토큰 401 · CUSTOMER 403 이고, ADMIN 은 매핑이 없어 404 다 — 어느 쪽도 컨트롤러에 닿지 않는다")
    void trailingSlashAdminMembersPath(CapturedOutput output) throws Exception {
        String trailing = ADMIN_MEMBERS + "/";

        assertEnvelope(perform(get(trailing)), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get(trailing).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
        assertThat(perform(get(trailing).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getStatus()).isEqualTo(404);
        assertThat(output.getOut()).doesNotContain("action=MEMBER_SEARCH");
    }
}
