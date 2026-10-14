package com.commercefoundry.security.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * access 토큰 검증 설정 ({@code security.jwt.*}). 검증하는 모든 곳(서비스 · 게이트웨이 · auth)이 쓴다.
 *
 * <p>공개키는 비밀이 아니지만 키 교체 때 Vault 한 곳에서 바꾸려고 환경변수로 받는다. 키 형식 검사는
 * 디코더를 만들 때({@link RsaKeys}) 하고, 여기서는 키가 아닌 값만 검사한다. 어긋나면 기동하지 않는다.
 *
 * @param issuer            {@code iss}. 기본 {@code commerce-foundry-auth}
 * @param audience          {@code aud} 에 들어 있어야 하는 값. 기본 {@code commerce-foundry-api}
 * @param clockSkew         만료 판정의 시계 오차 허용. 0 이상 5분 이하, 기본 60초
 * @param publicKey         현재 공개키 PEM ({@code JWT_PUBLIC_KEY})
 * @param previousPublicKey 키 교체 중에만 채우는 이전 공개키 PEM ({@code JWT_PUBLIC_KEY_PREVIOUS}). 평소에는 비어 있다
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtVerificationProperties(
    @DefaultValue("commerce-foundry-auth") String issuer,
    @DefaultValue("commerce-foundry-api") String audience,
    @DefaultValue("60s") Duration clockSkew,
    String publicKey,
    String previousPublicKey
) {

    public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    public JwtVerificationProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("security.jwt.issuer 가 비었습니다");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("security.jwt.audience 가 비었습니다");
        }
        if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new IllegalStateException("security.jwt.clock-skew 는 0 이상 5분 이하여야 합니다");
        }
    }
}
