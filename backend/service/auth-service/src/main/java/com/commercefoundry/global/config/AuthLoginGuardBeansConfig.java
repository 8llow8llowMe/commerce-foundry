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
 *
 * <ul>
 *   <li>{@code enabled} → {@link LoginGuardPolicy#enabled()}. 끄면 계수기를 부르지 않는다(Redis 키도 HMAC 키도 쓰지 않는다).
 *       운영에서 끄는 방법은 환경변수 {@code AUTH_LOGIN_GUARD_ENABLED=false} 하나다(application.yml 의 플레이스홀더, 기본 켬).</li>
 *   <li>{@code max-attempts} · {@code window} → {@link LoginGuardPolicy#login()} — 고객 로그인({@code POST /api/v1/auth/login}) ·
 *       관리자 로그인({@code POST /api/v1/auth/admin/login})이 같은 값을 쓰고 창은 따로 센다.</li>
 *   <li>{@code sign-up.*} → {@link LoginGuardPolicy#signUp()} — 회원가입({@code POST /api/v1/auth/sign-up}).</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AuthLoginGuardBeansConfig {

    /** 기존 소셜 계정 연결 재인증(#355)이 사용하는 로그인 정책 계약을 보존한다. */
    @Bean
    public LoginAttemptPolicy loginAttemptPolicy(LoginGuardPolicy policy) {
        return policy.login();
    }

    /** 꺼진 채 뜨면 기동 로그를 한 번 남긴다 — 로그인 · 가입 시도 제한이 없는 상태가 조용히 배포되지 않게 (#66 A2 전제 6). 문구는 로그 검색 키다. */
    @Bean
    public LoginGuardPolicy loginGuardPolicy(AuthLoginGuardProperties authLoginGuardProperties) {
        if (!authLoginGuardProperties.enabled()) {
            log.warn("login guard disabled. property=auth.login-guard.enabled");
        }
        AuthLoginGuardProperties.SignUp signUp = authLoginGuardProperties.signUp();
        return new LoginGuardPolicy(
            authLoginGuardProperties.enabled(),
            new LoginAttemptPolicy(authLoginGuardProperties.maxAttempts(), authLoginGuardProperties.window()),
            new LoginAttemptPolicy(signUp.maxAttempts(), signUp.window()),
            new LoginAttemptPolicy(signUp.clientIp().maxAttempts(), signUp.clientIp().window())
        );
    }
}
