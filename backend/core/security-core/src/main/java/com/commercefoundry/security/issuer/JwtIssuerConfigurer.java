package com.commercefoundry.security.issuer;

import com.commercefoundry.security.token.JwtVerificationProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;

/**
 * auth-service 의 {@code *BeansConfig} 가 {@code ResourceServerSecurityConfigurer} 와 함께 {@code @Import} 한다.
 * 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다.
 *
 * <p>개인키 형식 · 길이와 공개키와의 쌍이 맞지 않으면 {@link JwtIssuer#create} 가 기동을 멈춘다.
 */
public class JwtIssuerConfigurer {

    @Bean
    public JwtIssuer jwtIssuer(JwtVerificationProperties verificationProperties, JwtIssuanceProperties issuanceProperties) {
        return JwtIssuer.create(verificationProperties, issuanceProperties, Clock.systemUTC());
    }

    @Bean
    public OpaqueTokenGenerator opaqueTokenGenerator() {
        return new OpaqueTokenGenerator();
    }
}
