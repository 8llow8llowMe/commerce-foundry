package com.commercefoundry.security.resourceserver;

import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.internal.InternalTokenProperties;
import com.commercefoundry.security.internal.ServiceJwtAuthenticationConverter;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer.ServiceFailureAsEnvelope;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서비스 간 internal API({@code /internal/**})의 Resource Server 설정 — 서비스 토큰 인증 1단계 ({@code security-guide.md} §9).
 * 서비스의 {@code *BeansConfig} 가 {@link ResourceServerSecurityConfigurer} 와 함께 {@code @Import} 하고, {@code *PropertiesConfig} 가
 * {@code InternalTokenPropertiesConfig} 를 {@code @Import} 한다. 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다.
 *
 * <ul>
 *   <li>{@code securityMatcher("/internal/**")} 전용 체인이고 사용자 체인보다 <b>앞 순서</b>({@link #FILTER_CHAIN_ORDER})다.
 *       사용자 체인은 모든 요청에 걸리므로 마지막이어야 한다 — 순서가 뒤집히면 Spring Security 가 기동을 멈춘다.</li>
 *   <li>모든 요청이 인증을 요구한다. 인증 수단은 서비스 토큰(HS256 · {@code typ=svc+jwt} · {@code aud} 에 이 서비스)뿐이다.
 *       토큰 없음 · 서명 · 만료 · {@code typ} · {@code aud} 불일치 · 사용자 access 토큰은 모두 401 · {@code SECURITY_007} 이다.</li>
 *   <li>호출자 허용 목록은 컨트롤러 메서드의 {@code @PreAuthorize("hasAuthority('SERVICE_order-service')")} 가 정하고, 거부는 403 · {@code SECURITY_005} 다
 *       ({@link SecurityExceptionAdvice}). internal 핸들러마다 반드시 선언한다.</li>
 *   <li>STATELESS. CSRF · 폼 로그인 · Basic · 로그아웃 · CORS · 요청 캐시를 끈다.</li>
 *   <li>디코더는 이 체인 안에서만 만든다 — 두 번째 {@code JwtDecoder} 빈을 두면 사용자 체인의 {@code accessTokenDecoder} 주입과 Boot 자동설정이 깨진다.</li>
 * </ul>
 *
 * <p>사용자 체인({@link ResourceServerSecurityConfigurer})을 함께 가져온다 — 이 체인만 있으면 {@code /internal/**} 밖의 요청에는 체인이 없어
 * 모두 열리고 {@code @PreAuthorize} 도 꺼진다. 서비스는 두 Configurer 를 모두 명시적으로 import 한다(같은 클래스를 두 번 import 해도 한 번만 등록된다).
 * 반대로 이 Configurer 를 빠뜨리면 사용자 체인의 {@code denyAll} 이 internal API 를 닫는다(fail-closed).
 */
@Import(ResourceServerSecurityConfigurer.class)
public class InternalResourceServerConfigurer {

    public static final String INTERNAL_PATH_PATTERN = "/internal/**";

    /** 사용자 체인(순서 미지정 = {@link Ordered#LOWEST_PRECEDENCE})보다 앞이면 된다. 다른 경로 전용 체인이 생겨도 경로가 겹치지 않아 서로의 순서는 상관없다. */
    public static final int FILTER_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE;

    @Bean
    @Order(FILTER_CHAIN_ORDER)
    public SecurityFilterChain internalSecurityFilterChain(
        HttpSecurity http, InternalTokenProperties internalTokenProperties, JsonMapper jsonMapper
    ) throws Exception {
        SecurityErrorResponder responder = SecurityErrorResponder.forServiceAuthentication(new SecurityErrorEnvelope(jsonMapper));
        // 빈으로 두지 않는다 — 사용자 체인의 JwtDecoder 빈과 겹친다
        JwtDecoder serviceTokenDecoder = InternalTokenDecoderFactory.create(internalTokenProperties, Clock.systemUTC());
        // Converter 빈으로 두면 MVC 변환 서비스에도 등록된다. 여기서만 쓰므로 직접 만든다
        ServiceJwtAuthenticationConverter authenticationConverter = new ServiceJwtAuthenticationConverter();

        return http
            .securityMatcher(INTERNAL_PATH_PATTERN)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.decoder(serviceTokenDecoder).jwtAuthenticationConverter(authenticationConverter))
                .authenticationEntryPoint(responder)
                .accessDeniedHandler(responder)
                .withObjectPostProcessor(new ServiceFailureAsEnvelope(responder)))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(responder)
                .accessDeniedHandler(responder))
            .build();
    }
}
