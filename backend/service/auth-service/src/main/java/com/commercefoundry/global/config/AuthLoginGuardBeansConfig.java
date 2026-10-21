package com.commercefoundry.global.config;

import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptPolicy;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 로그인 · 회원가입 시도 제한 정책 빈 ({@code auth.login-guard.*}, #66). 범위 검사는 프로퍼티가 바인딩에서 먼저 한다 —
 * 정책 빈은 같은 범위를 한 번 더 지키는 값 묶음이다.
 */
@Configuration
public class AuthLoginGuardBeansConfig {

    @Bean
    public LoginAttemptPolicy loginAttemptPolicy(AuthLoginGuardProperties authLoginGuardProperties) {
        return new LoginAttemptPolicy(authLoginGuardProperties.maxAttempts(), authLoginGuardProperties.window());
    }
}
