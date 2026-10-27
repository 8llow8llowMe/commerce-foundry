package com.commercefoundry.domainlayer.member.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.*;
import com.commercefoundry.domainlayer.member.application.port.in.SocialAuthWebUseCase;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(SocialAuthWebController.class)
@Import({JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class})
class SocialAuthWebControllerTest {
    @Autowired MockMvcTester mvc;
    @MockitoBean SocialAuthWebUseCase useCase;

    @Test
    void anonymousAuthorizeAndLoginUseNoStoreEnvelope() {
        when(useCase.authorize("kakao")).thenReturn(new SocialAuthorizationResponse("https://example.com", "state", "verifier"));
        var authorize = mvc.get().uri("/api/v1/auth/oauth/kakao/authorize-url").exchange();
        assertThat(authorize).hasStatusOk();
        assertThat(authorize.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        when(useCase.login(eq("kakao"), any())).thenReturn(AuthTokenResponse.builder().accessToken("access").refreshToken("refresh").build());
        var login = mvc.post().uri("/api/v1/auth/oauth/kakao/login").contentType(MediaType.APPLICATION_JSON).content(body("")).exchange();
        assertThat(login).hasStatusOk();
        assertThat(login.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(login).bodyJson().extractingPath("$.dataBody.accessToken").isEqualTo("access");
    }

    @Test
    void requiredSignupConsentCannotBeFalse() {
        var result = mvc.post().uri("/api/v1/auth/oauth/naver/login").contentType(MediaType.APPLICATION_JSON)
            .content(body(",\"agreements\":{\"termsOfService\":false,\"privacyPolicy\":true,\"marketing\":false}")).exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_111");
        verifyNoInteractions(useCase);
    }

    @Test
    void malformedStateIsValidationErrorBeforeUseCase() {
        var result = mvc.post().uri("/api/v1/auth/oauth/naver/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"code\",\"state\":\"bad\",\"verifier\":\"" + "v".repeat(43) + "\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_151");
        verifyNoInteractions(useCase);
    }

    private String body(String suffix) {
        return "{\"code\":\"code\",\"state\":\"" + "s".repeat(43) + "\",\"verifier\":\"" + "v".repeat(43) + "\"" + suffix + "}";
    }
}