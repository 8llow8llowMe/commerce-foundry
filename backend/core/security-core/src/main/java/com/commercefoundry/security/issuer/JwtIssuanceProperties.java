package com.commercefoundry.security.issuer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 토큰 발급 설정 ({@code security.jwt.issuance.*}). auth-service 만 쓴다.
 *
 * @param privateKey      서명 개인키 PEM ({@code JWT_PRIVATE_KEY}). 형식 검사는 {@link JwtIssuer} 를 만들 때 한다
 * @param accessTokenTtl  access 토큰 수명. 기본 30분, 최대 1시간
 * @param refreshTokenTtl refresh 토큰 수명. 기본 14일. 저장·회전은 auth-service(#4)가 한다
 */
@ConfigurationProperties(prefix = "security.jwt.issuance")
public record JwtIssuanceProperties(
    String privateKey,
    @DefaultValue("30m") Duration accessTokenTtl,
    @DefaultValue("14d") Duration refreshTokenTtl
) {

    /**
     * access 토큰은 서명만으로 통과해 발급 뒤에는 거둘 수 없다. 길수록 탈취된 토큰을 쓸 수 있는 시간과
     * 로그아웃 블랙리스트(#4)가 jti 를 들고 있어야 하는 시간이 함께 늘어난다.
     */
    public static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofHours(1);

    public JwtIssuanceProperties {
        requirePositive(accessTokenTtl, "security.jwt.issuance.access-token-ttl");
        requirePositive(refreshTokenTtl, "security.jwt.issuance.refresh-token-ttl");
        if (accessTokenTtl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0) {
            throw new IllegalStateException("security.jwt.issuance.access-token-ttl 은 1시간 이하여야 합니다"
                + " — access 토큰은 발급 뒤 거둘 수 없어 길수록 탈취 시 악용 창과 로그아웃 블랙리스트 보관 기간이 늘어납니다. 긴 로그인 유지는 refresh 토큰으로 합니다");
        }
    }

    /** 개인키가 로그·오류 메시지로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "JwtIssuanceProperties[privateKey=" + (privateKey == null ? "null" : "****")
            + ", accessTokenTtl=" + accessTokenTtl + ", refreshTokenTtl=" + refreshTokenTtl + "]";
    }

    private static void requirePositive(Duration ttl, String property) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException(property + " 는 0 보다 커야 합니다");
        }
    }
}
