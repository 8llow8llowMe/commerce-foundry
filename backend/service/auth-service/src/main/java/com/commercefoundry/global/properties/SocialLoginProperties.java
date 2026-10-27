package com.commercefoundry.global.properties;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "auth.social")
public record SocialLoginProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("5m") Duration stateTtl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("3s") Duration readTimeout,
    @DefaultValue Provider kakao,
    @DefaultValue Provider naver
) {
    public SocialLoginProperties {
        if (stateTtl == null || stateTtl.compareTo(Duration.ofMinutes(1)) < 0 || stateTtl.compareTo(Duration.ofMinutes(10)) > 0
            || !validTimeout(connectTimeout) || !validTimeout(readTimeout)) {
            throw new IllegalArgumentException("auth.social TTL 또는 timeout 설정이 올바르지 않다");
        }
        if (enabled) {
            if (kakao == null || naver == null || (!kakao.enabled() && !naver.enabled())) {
                throw new IllegalArgumentException("auth.social 제공자가 필요하다");
            }
            if (kakao.enabled()) {
                validateProvider(kakao);
            }
            if (naver.enabled()) {
                validateProvider(naver);
            }
        }
    }

    public Provider provider(com.commercefoundry.domainlayer.member.domain.model.SocialProvider provider) {
        return switch (provider) {
            case KAKAO -> kakao;
            case NAVER -> naver;
        };
    }

    private static boolean validTimeout(Duration value) {
        return value != null && value.toMillis() >= 100 && value.compareTo(Duration.ofSeconds(10)) <= 0;
    }

    private static void validateProvider(Provider provider) {
        if (!configured(provider.clientId()) || !configured(provider.clientSecret()) || !configured(provider.redirectUri())) {
            throw new IllegalArgumentException("auth.social 제공자 자격 증명 또는 redirect-uri가 없다");
        }
        URI uri;
        try {
            uri = URI.create(provider.redirectUri());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("auth.social redirect-uri가 올바르지 않다");
        }
        boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
            || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) {
            throw new IllegalArgumentException("auth.social redirect-uri는 HTTPS 또는 loopback HTTP여야 한다");
        }
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank() && !value.contains("${") && value.equals(value.strip());
    }

    public record Provider(@DefaultValue("false") boolean enabled, String clientId, String clientSecret, String redirectUri) {
        @Override
        public String toString() {
            return "SocialProviderConfig[enabled=" + enabled + "]";
        }
    }
}