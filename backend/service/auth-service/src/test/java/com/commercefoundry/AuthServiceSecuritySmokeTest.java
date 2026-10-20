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
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 */
class AuthServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    private static final String ME = "/api/v1/members/me";
    private static final String SHIPPING_ADDRESSES = ME + "/shipping-addresses";
    private static final String VALID_SHIPPING_ADDRESS = """
        {"recipientName":"홍길동","recipientPhone":"01012345678","countryCode":"KR","postalCode":"06236","addressLine1":"서울특별시 강남구 테헤란로 123"}""";

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
}
