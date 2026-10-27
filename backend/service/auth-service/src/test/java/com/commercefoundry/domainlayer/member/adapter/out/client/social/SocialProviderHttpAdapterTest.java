package com.commercefoundry.domainlayer.member.adapter.out.client.social;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import com.commercefoundry.global.properties.SocialLoginProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SocialProviderHttpAdapterTest {
    private final HttpClient http = mock(HttpClient.class);
    private final SocialLoginProperties.Provider config = new SocialLoginProperties.Provider(true, "client", "secret", "https://shop.example/callback");
    private final SocialLoginProperties properties = new SocialLoginProperties(true, Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(3), config, config);
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private final SocialProviderHttpAdapter adapter = new SocialProviderHttpAdapter(properties, registry, JsonMapper.builder().build(), http);

    @Test
    void kakaoRequiresBothEmailTrustFlagsAndUsesBearerOnlyForProfile() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(
            response(200, "{\"access_token\":\"sensitive-provider-token\"}"),
            response(200, "{\"id\":123,\"kakao_account\":{\"email\":\"customer@example.com\",\"is_email_valid\":true,\"is_email_verified\":false}}"));
        var profile = adapter.authenticate(SocialProvider.KAKAO, "code", "state");
        assertThat(profile.subject()).isEqualTo("123");
        assertThat(profile.usableEmail()).isFalse();
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues().getFirst().headers().firstValue("Authorization")).isEmpty();
        assertThat(requests.getAllValues().getLast().headers().firstValue("Authorization")).contains("Bearer sensitive-provider-token");
        assertThat(requests.getAllValues()).allSatisfy(request -> assertThat(request.timeout()).contains(Duration.ofSeconds(3)));
    }

    @Test
    void naverUsesIdEvenWhenEmailMissing() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(
            response(200, "{\"access_token\":\"token\"}"), response(200, "{\"resultcode\":\"00\",\"response\":{\"id\":\"stable-id\"}}"));
        var profile = adapter.authenticate(SocialProvider.NAVER, "code", "state");
        assertThat(profile.subject()).isEqualTo("stable-id");
        assertThat(profile.usableEmail()).isFalse();
    }

    @Test
    void timeoutAndServerFailureHaveSanitized503() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("secret code token"));
        assertThatThrownBy(() -> adapter.authenticate(SocialProvider.KAKAO, "code", "state")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        assertThatThrownBy(() -> adapter.authenticate(SocialProvider.KAKAO, "code", "state")).hasNoCause().hasMessageNotContaining("secret");
    }

    @Test
    void invalidCodeIs400AndMissingIdIs503() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response(400, "private provider body"));
        assertThatThrownBy(() -> adapter.authenticate(SocialProvider.NAVER, "code", "state")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_CODE_INVALID);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(
            response(200, "{\"access_token\":\"token\"}"), response(200, "{\"resultcode\":\"00\",\"response\":{}}"));
        assertThatThrownBy(() -> adapter.authenticate(SocialProvider.NAVER, "code", "state")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
    }

    @Test
    void authorizeUrlContainsStateAndConfiguredRedirectOnly() {
        assertThat(adapter.authorizeUrl(SocialProvider.NAVER, "opaque-state")).contains("state=opaque-state", "client_id=client")
            .doesNotContain("secret", "verifier");
    }

    @Test
    void openCircuitDoesNotSendRequestAnd429CountsAsProviderFailure() throws Exception {
        registry.circuitBreaker("kakao").transitionToOpenState();
        assertThatThrownBy(() -> adapter.authenticate(SocialProvider.KAKAO, "code", "state")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        verifyNoInteractions(http);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response(429, "private"));
        assertThatThrownBy(() -> adapter.authenticate(SocialProvider.NAVER, "code", "state")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        assertThat(registry.circuitBreaker("naver").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private HttpResponse<String> response(int status, String body) {
        return mock(HttpResponse.class, invocation -> switch (invocation.getMethod().getName()) {
            case "statusCode" -> status;
            case "body" -> body;
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
