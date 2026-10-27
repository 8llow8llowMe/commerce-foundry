package com.commercefoundry.domainlayer.member.adapter.out.client.social;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.SocialProviderPort;
import com.commercefoundry.domainlayer.member.domain.model.SocialProfile;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import com.commercefoundry.global.properties.SocialLoginProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 제공자 토큰은 이 호출의 지역 변수로만 사용한다. 응답 · 자격 증명 · HTTP 예외 원문은 로그/예외에 싣지 않는다. */
@Component
public class SocialProviderHttpAdapter implements SocialProviderPort {
    private final SocialLoginProperties properties;
    private final CircuitBreakerRegistry circuits;
    private final JsonMapper json;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public SocialProviderHttpAdapter(SocialLoginProperties properties, CircuitBreakerRegistry circuits, JsonMapper json) {
        this(properties, circuits, json, HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    SocialProviderHttpAdapter(SocialLoginProperties properties, CircuitBreakerRegistry circuits, JsonMapper json, HttpClient http) {
        this.properties = properties;
        this.circuits = circuits;
        this.json = json;
        this.http = http;
    }

    @Override
    public void requireEnabled(SocialProvider provider) {
        if (!properties.enabled() || properties.provider(provider) == null || !properties.provider(provider).enabled()) {
            throw new MemberException(MemberErrorCode.SOCIAL_DISABLED);
        }
    }

    @Override
    public String authorizeUrl(SocialProvider provider, String state) {
        requireEnabled(provider);
        SocialLoginProperties.Provider config = properties.provider(provider);
        String endpoint = provider == SocialProvider.KAKAO ? "https://kauth.kakao.com/oauth/authorize" : "https://nid.naver.com/oauth2.0/authorize";
        return endpoint + "?" + form(Map.of("response_type", "code", "client_id", config.clientId(), "redirect_uri", config.redirectUri(), "state", state));
    }

    @Override
    public SocialProfile authenticate(SocialProvider provider, String code, String state) {
        requireEnabled(provider);
        try {
            return circuits.circuitBreaker(provider.name().toLowerCase(java.util.Locale.ROOT)).executeSupplier(() -> profile(provider, code, state));
        } catch (Rejected rejected) {
            throw new MemberException(MemberErrorCode.SOCIAL_CODE_INVALID);
        } catch (RuntimeException unavailable) {
            throw new MemberException(MemberErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
        }
    }

    private SocialProfile profile(SocialProvider provider, String code, String state) {
        SocialLoginProperties.Provider config = properties.provider(provider);
        String tokenUrl = provider == SocialProvider.KAKAO ? "https://kauth.kakao.com/oauth/token" : "https://nid.naver.com/oauth2.0/token";
        String body = form(Map.of("grant_type", "authorization_code", "client_id", config.clientId(), "client_secret", config.clientSecret(),
            "redirect_uri", config.redirectUri(), "code", code, "state", state));
        TokenResponse token = json.readValue(send(HttpRequest.newBuilder(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(body))), TokenResponse.class);
        if (token.error() != null) {
            throw new Rejected();
        }
        if (token.accessToken() == null || token.accessToken().isBlank()) {
            throw new IllegalStateException("소셜 토큰 응답 계약 오류");
        }
        String profileUrl = provider == SocialProvider.KAKAO ? "https://kapi.kakao.com/v2/user/me" : "https://openapi.naver.com/v1/nid/me";
        String profileBody = send(HttpRequest.newBuilder(URI.create(profileUrl)).header("Authorization", "Bearer " + token.accessToken()).GET());
        if (provider == SocialProvider.KAKAO) {
            KakaoProfile response = json.readValue(profileBody, KakaoProfile.class);
            KakaoAccount account = response.account();
            return new SocialProfile(provider, response.id() == null ? null : response.id().toString(),
                account == null ? null : account.email(), account != null && Boolean.TRUE.equals(account.valid()) && Boolean.TRUE.equals(account.verified()),
                account == null || account.profile() == null ? null : account.profile().nickname());
        }
        NaverEnvelope response = json.readValue(profileBody, NaverEnvelope.class);
        if (!"00".equals(response.resultcode()) || response.response() == null) {
            throw new IllegalStateException("소셜 프로필 응답 계약 오류");
        }
        NaverProfile account = response.response();
        // Naver는 email_verified 필드가 없다. id로 식별하며 이메일은 신규 가입 정보에만 쓴다.
        return new SocialProfile(provider, account.id(), account.email(), account.email() != null && !account.email().isBlank(), account.name());
    }

    private String send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.timeout(properties.readTimeout()).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400 && response.statusCode() < 500 && response.statusCode() != 429) {
                throw new Rejected();
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("소셜 제공자 HTTP 실패");
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("소셜 제공자 호출 중단");
        } catch (IOException unavailable) {
            throw new IllegalStateException("소셜 제공자 연결 실패");
        }
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 4xx는 제공자 장애로 세지 않는다. 응답 본문/URL 없이 분류만 전달한다. */
    public static final class Rejected extends RuntimeException {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") String accessToken, String error) {
        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoProfile(Long id, @JsonProperty("kakao_account") KakaoAccount account) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoAccount(String email, @JsonProperty("is_email_valid") Boolean valid,
        @JsonProperty("is_email_verified") Boolean verified, KakaoNickname profile) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoNickname(String nickname) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverEnvelope(String resultcode, NaverProfile response) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverProfile(String id, String email, String name) {
    }
}