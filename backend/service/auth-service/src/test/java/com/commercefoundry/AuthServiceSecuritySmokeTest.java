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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 인가 스모크 — security-core Resource Server 가 이 서비스에 실제로 걸렸는지 본다 ({@code security-guide.md} §5-3).
 * 애노테이션은 {@link AuthServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 *
 * <p>{@code isAuthenticated()} 핸들러(내 정보)의 익명 401 과 공개 핸들러(회원가입)의 {@code permitAll()} 을 실제 빈 구성으로 본다.
 * {@code issuerExpected()} 는 발급기({@code JwtIssuerConfigurer})를 import 하는 #4 A3 에서 true 로 바꾼다.
 */
class AuthServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    private static final String ME = "/api/v1/members/me";

    @Test
    @DisplayName("내 정보 조회는 토큰 없으면 401 · SECURITY_001 · WWW-Authenticate: Bearer 다 (isAuthenticated)")
    void anonymousGetMe() throws Exception {
        MockHttpServletResponse response = perform(get(ME));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
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
