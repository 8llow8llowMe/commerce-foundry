package com.commercefoundry.discovery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Eureka 레지스트리 인증 (#248, {@code security-guide.md} §12). 같은 docker 네트워크의 아무 컨테이너나 가짜 인스턴스를 등록하지 못하게
 * 등록 · 조회({@code /eureka/**})와 대시보드를 HTTP Basic 으로 닫는다.
 *
 * <ul>
 *   <li>계정은 하나다 — 사용자 {@value #USERNAME}, 비밀번호 {@code EUREKA_PASSWORD}. 게이트웨이와 서비스가 같은 값을 {@code defaultZone} URL 에 싣는다.
 *       Eureka 는 계정과 서비스 이름을 묶지 않아 서비스별 계정은 의미가 없다.</li>
 *   <li>{@code /actuator/health} 만 익명으로 연다(compose healthcheck). 다른 actuator 는 막는다.</li>
 *   <li>세션 · CSRF · 폼 로그인을 끈다 — 쿠키 세션이 없고, 상태를 바꾸는 호출자는 Basic 을 명시적으로 싣는 클라이언트뿐이다.</li>
 *   <li>비밀번호는 BCrypt 로 해시해 메모리에 둔다({@code {noop}} 평문 저장 금지). 형식은 {@link DiscoveryPasswordStartupGuard} 가 먼저 본다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class DiscoverySecurityConfig {

    /** 비밀이 아니다 — 7개 클라이언트 yml 의 {@code defaultZone} 에 같은 리터럴로 들어 있다. */
    public static final String USERNAME = "eureka";
    private static final String HEALTH_ENDPOINT = "health";

    @Bean
    public static DiscoveryPasswordStartupGuard discoveryPasswordStartupGuard(Environment environment) {
        return new DiscoveryPasswordStartupGuard(environment);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 이 빈이 있으면 Boot 가 기본 사용자(생성 비밀번호)를 만들지 않는다. */
    @Bean
    public UserDetailsService eurekaClientUsers(
        @Value("${" + DiscoveryPasswordStartupGuard.PASSWORD + "}") String password, PasswordEncoder passwordEncoder
    ) {
        return new InMemoryUserDetailsManager(User.withUsername(USERNAME).password(passwordEncoder.encode(password)).roles("EUREKA_CLIENT").build());
    }

    @Bean
    public SecurityFilterChain discoverySecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .httpBasic(Customizer.withDefaults())
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(EndpointRequest.to(HEALTH_ENDPOINT)).permitAll()
                .requestMatchers(EndpointRequest.toAnyEndpoint().excluding(HEALTH_ENDPOINT)).denyAll()
                // /eureka/** (등록 · 조회 · heartbeat) · 대시보드 · 정적 자원
                .anyRequest().authenticated())
            .build();
    }
}
