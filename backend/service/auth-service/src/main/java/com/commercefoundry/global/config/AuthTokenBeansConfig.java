package com.commercefoundry.global.config;

import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.global.properties.AuthTokenProperties;
import com.commercefoundry.security.issuer.JwtIssuanceProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * refresh 토큰 회전 정책 빈. {@code auth.token.*} 와 발급 설정 {@code security.jwt.issuance.refresh-token-ttl} 이 만나는 곳이라
 * 두 설정을 함께 봐야 하는 검사를 여기서 한다 — 싱글톤 빈이라 어긋나면 첫 재발급이 아니라 기동에서 실패한다.
 */
@Configuration
public class AuthTokenBeansConfig {

    /**
     * @throws IllegalStateException family 절대 수명이 refresh TTL 보다 짧다 — 로그인 직후부터 TTL 이 줄어 refresh TTL 설정이 뜻을 잃는다
     */
    @Bean
    public RefreshTokenRotationPolicy refreshTokenRotationPolicy(AuthTokenProperties authTokenProperties, JwtIssuanceProperties jwtIssuanceProperties) {
        Duration refreshTokenTtl = jwtIssuanceProperties.refreshTokenTtl();
        if (authTokenProperties.refreshFamilyMaxLifetime().compareTo(refreshTokenTtl) < 0) {
            throw new IllegalStateException("auth.token.refresh-family-max-lifetime 은 security.jwt.issuance.refresh-token-ttl 이상이어야 합니다"
                + " — 짧으면 로그인 직후부터 refresh 토큰 TTL 이 줄어 설정이 뜻을 잃습니다");
        }
        return new RefreshTokenRotationPolicy(
            authTokenProperties.refreshReuseGrace(), authTokenProperties.refreshReuseGraceMaxSiblings(), refreshTokenTtl, authTokenProperties.refreshFamilyMaxLifetime()
        );
    }
}
