package com.commercefoundry.global.config;

import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptPolicy;
import com.commercefoundry.domainlayer.member.domain.model.LoginGuardPolicy;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 로그인 · 회원가입 시도 제한 정책 빈 ({@code auth.login-guard.*}, #66). 범위 검사는 프로퍼티가 바인딩에서 먼저 한다 —
 * 정책 빈은 같은 범위를 한 번 더 지키는 값 묶음이다.
 */
@Slf4j
@Configuration
public class AuthLoginGuardBeansConfig {

    /** 꺼진 채 뜨면 기동 로그를 한 번 남긴다 — 로그인 · 가입 시도 제한이 없는 상태가 조용히 배포되지 않게 (#66 A2 전제 6). */
    @Bean
    public LoginGuardPolicy loginGuardPolicy(AuthLoginGuardProperties authLoginGuardProperties) {
        if (!authLoginGuardProperties.enabled()) {
            log.warn("auth.login-guard.enabled=false — 로그인 · 회원가입 시도 제한이 꺼진 채 기동한다");
        }
        AuthLoginGuardProperties.SignUp signUp = authLoginGuardProperties.signUp();
        return new LoginGuardPolicy(
            authLoginGuardProperties.enabled(),
            new LoginAttemptPolicy(authLoginGuardProperties.maxAttempts(), authLoginGuardProperties.window()),
            new LoginAttemptPolicy(signUp.maxAttempts(), signUp.window())
        );
    }
}
