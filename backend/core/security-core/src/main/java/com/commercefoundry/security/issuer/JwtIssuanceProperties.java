package com.commercefoundry.security.issuer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 토큰 발급 설정 ({@code security.jwt.issuance.*}). auth-service 만 쓴다.
 *
 * @param privateKey      서명 개인키 PEM ({@code JWT_PRIVATE_KEY}). 형식 검사는 {@link JwtIssuer} 를 만들 때 한다
 * @param accessTokenTtl  access 토큰 수명. 기본 30분
 * @param refreshTokenTtl refresh 토큰 수명. 기본 14일. 저장·회전은 auth-service(#4)가 한다
 */
@ConfigurationProperties(prefix = "security.jwt.issuance")
public record JwtIssuanceProperties(
    String privateKey,
    @DefaultValue("30m") Duration accessTokenTtl,
    @DefaultValue("14d") Duration refreshTokenTtl
) {

    public JwtIssuanceProperties {
        requirePositive(accessTokenTtl, "security.jwt.issuance.access-token-ttl");
        requirePositive(refreshTokenTtl, "security.jwt.issuance.refresh-token-ttl");
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
