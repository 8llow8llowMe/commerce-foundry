package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.*;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.request.*;
import com.commercefoundry.domainlayer.member.application.port.in.SocialAccountWebUseCase;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(SocialAccountWebController.class)
@Import({JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class})
class SocialAccountWebControllerTest {
    private static final String BASE = "/api/v1/members/me/social-accounts";
    @Autowired MockMvcTester mvc;
    @MockitoBean SocialAccountWebUseCase useCase;

    private String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(10, role).tokenValue();
    }

    @Test
    void anonymousAndAdminCannotUseAnyLinkEndpoint() {
        assertThat(mvc.get().uri(BASE).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange()).hasStatus(HttpStatus.FORBIDDEN);
        for (String path : List.of("/kakao", "/kakao/authorize-url")) {
            String body = path.endsWith("authorize-url") ? "{\"currentPassword\":\"password\"}" : body();
            assertThat(mvc.post().uri(BASE + path).contentType(MediaType.APPLICATION_JSON).content(body).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(mvc.post().uri(BASE + path).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange()).hasStatus(HttpStatus.FORBIDDEN);
        }
        verifyNoInteractions(useCase);
    }

    @Test
    void customerIdFromJwtAndMinimalNoStoreResponses() throws Exception {
        when(useCase.list(10)).thenReturn(new SocialAccountsResponse(List.of("kakao")));
        var result = mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.getResponse().getContentAsString()).contains("kakao").doesNotContain("subject", "email", "token", "verifier");
        when(useCase.authorize(10, "kakao", "password")).thenReturn(new SocialAuthorizationResponse("https://example.com", "state", "verifier"));
        var authorize = mvc.post().uri(BASE + "/kakao/authorize-url").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"password\"}").exchange();
        assertThat(authorize).hasStatusOk();
        assertThat(authorize.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        when(useCase.link(10, "kakao", "code", "s".repeat(43), "v".repeat(43))).thenReturn(new SocialAccountsResponse(List.of("kakao")));
        var linked = mvc.post().uri(BASE + "/kakao").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content(body()).exchange();
        assertThat(linked).hasStatusOk();
        assertThat(linked.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        verify(useCase).link(10, "kakao", "code", "s".repeat(43), "v".repeat(43));
    }

    @Test
    void invalidRequestNeverReachesUseCaseAndSecretsHaveRedactedToString() {
        assertThat(mvc.post().uri(BASE + "/kakao").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"code\",\"state\":\"bad\",\"verifier\":\"bad\"}").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri(BASE + "/kakao/authorize-url").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"\"}").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(useCase);
        assertThat(new SocialAccountAuthorizationRequest("secret").toString()).doesNotContain("secret");
        assertThat(new SocialAccountLinkRequest("code", "state", "verifier").toString()).doesNotContain("code", "state", "verifier");
    }

    private String body() {
        return "{\"code\":\"code\",\"state\":\"" + "s".repeat(43) + "\",\"verifier\":\"" + "v".repeat(43) + "\"}";
    }
}
