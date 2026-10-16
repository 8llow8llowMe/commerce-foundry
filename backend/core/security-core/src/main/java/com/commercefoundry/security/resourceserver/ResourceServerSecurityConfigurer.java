package com.commercefoundry.security.resourceserver;

import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.principal.MemberJwtAuthenticationConverter;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.token.AccessTokenDecoderFactory;
import com.commercefoundry.security.token.JwtVerificationProperties;
import java.time.Clock;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서블릿 서비스의 Resource Server 설정. 서비스의 {@code *BeansConfig} 가 {@code @Import} 하고, {@code *PropertiesConfig} 가
 * {@code JwtVerificationPropertiesConfig} 를 {@code @Import} 한다 ({@code security-guide.md} Resource Server).
 * 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다 — lite 모드라 {@code @Bean} 끼리 메서드를 부르지 않고 파라미터로 받는다.
 *
 * <ul>
 *   <li>세션을 만들지 않는다(STATELESS). CSRF · 폼 로그인 · Basic · 로그아웃 · CORS(게이트웨이 몫)를 끈다.</li>
 *   <li>actuator 는 {@code health}(하위 경로 포함)만 열고, 웹에 노출된 다른 엔드포인트와 링크({@code /actuator})는 막는다(익명 001 · 로그인 005).
 *       노출하지 않은 엔드포인트는 이 규칙에 걸리지 않고 404 다 — 노출 설정이 1차, URL 규칙이 2차 방어다. management base-path 를 바꿔도 따라간다.
 *       실행 모듈은 actuator 를 가진다는 전제다({@code backend/build.gradle} 이 실행 모듈마다 넣는다).</li>
 *   <li>{@code /api/v1/admin/**} 은 URL 규칙으로 ADMIN 을 요구한다. 컨트롤러의 {@code hasRole('ADMIN')} 과 이중이다.</li>
 *   <li>{@code /internal/**} 은 이 체인에서 {@code denyAll} 이다(익명 001 · 로그인 005). 서비스 토큰으로 여는 것은 그보다 앞 순서의
 *       {@link InternalResourceServerConfigurer} 체인이다 — 서비스가 그 import 를 빠뜨려도 internal API 가 열리지 않는다(fail-closed).
 *       internal 컨트롤러 메서드는 허용 호출자를 {@code @PreAuthorize("hasAuthority('SERVICE_<service-id>')")} 로 반드시 선언한다.</li>
 *   <li>나머지 경로는 열어 두고 인증 필요 여부는 메서드의 {@code @PreAuthorize} 가 정한다 — 비로그인 상품 조회 같은 API 가 있다.</li>
 *   <li>토큰이 있는데 틀리면 공개 API 여도 401 이다. 틀린 토큰을 익명으로 취급하지 않는다.</li>
 *   <li>토큰 검증 중 시스템 오류({@code AuthenticationServiceException})는 500 이 아니라 006(503) 봉투로 답한다.</li>
 * </ul>
 */
@EnableWebSecurity
@EnableMethodSecurity
public class ResourceServerSecurityConfigurer {

    public static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";
    /** 익명으로 여는 유일한 actuator 엔드포인트. 상태만 낸다(구성 요소 · 상세는 숨김 기본값). */
    public static final String HEALTH_ENDPOINT = "health";

    /** {@code JwtDecoder} 빈이 있으면 Boot 가 기본 사용자(생성 비밀번호)를 만들지 않는다. */
    @Bean
    public JwtDecoder accessTokenDecoder(JwtVerificationProperties properties) {
        return AccessTokenDecoderFactory.create(properties, Clock.systemUTC());
    }

    /**
     * 오류 본문은 서비스의 {@code JsonMapper} 빈(Boot 의 Jackson 자동설정, {@code spring-boot-starter-webmvc} 가 끌고 온다)으로 쓴다 —
     * 다른 오류 응답과 직렬화 설정이 같아야 한다. 빈이 없으면 기본 인스턴스로 몰래 바꾸지 않고 기동을 멈춘다.
     */
    @Bean
    public SecurityFilterChain resourceServerSecurityFilterChain(
        HttpSecurity http, JwtDecoder accessTokenDecoder, JsonMapper jsonMapper
    ) throws Exception {
        SecurityErrorResponder responder = new SecurityErrorResponder(new SecurityErrorEnvelope(jsonMapper));
        // Converter 빈으로 두면 MVC 변환 서비스에도 등록된다. 여기서만 쓰므로 직접 만든다
        MemberJwtAuthenticationConverter authenticationConverter = new MemberJwtAuthenticationConverter();

        return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(EndpointRequest.to(HEALTH_ENDPOINT)).permitAll()
                .requestMatchers(EndpointRequest.toAnyEndpoint().excluding(HEALTH_ENDPOINT)).denyAll()
                .requestMatchers(ADMIN_PATH_PATTERN).hasRole(MemberRole.ADMIN.name())
                // internal API 는 앞 순서의 서비스 인증 체인(InternalResourceServerConfigurer)만 연다. 그 체인이 없으면 여기서 닫힌다
                .requestMatchers(InternalResourceServerConfigurer.INTERNAL_PATH_PATTERN).denyAll()
                .anyRequest().permitAll())
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.decoder(accessTokenDecoder).jwtAuthenticationConverter(authenticationConverter))
                .authenticationEntryPoint(responder)
                .accessDeniedHandler(responder)
                .withObjectPostProcessor(new ServiceFailureAsEnvelope(responder)))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(responder)
                .accessDeniedHandler(responder))
            .build();
    }

    @Bean
    public SecurityExceptionAdvice securityExceptionAdvice() {
        return new SecurityExceptionAdvice();
    }

    /**
     * {@code BearerTokenAuthenticationFilter} 의 기본 실패 처리기는 {@code AuthenticationServiceException}(디코더의 {@code JwtException},
     * #4 의 검증 저장소 장애)을 다시 던진다 — 필터 밖으로 나가 봉투 없는 500 이 된다. 다시 던지지 않고 진입점(006, 503)으로 보낸다.
     * 리액티브 게이트웨이는 {@code ServerAuthenticationEntryPointFailureHandler} 에 같은 설정이 필요하다 ({@code security-guide.md} §7).
     * 서비스 간 인증 체인({@link InternalResourceServerConfigurer})도 같은 이유로 쓴다.
     */
    static final class ServiceFailureAsEnvelope implements ObjectPostProcessor<BearerTokenAuthenticationFilter> {

        private final SecurityErrorResponder responder;

        ServiceFailureAsEnvelope(SecurityErrorResponder responder) {
            this.responder = responder;
        }

        @Override
        public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
            AuthenticationEntryPointFailureHandler failureHandler = new AuthenticationEntryPointFailureHandler(responder);
            failureHandler.setRethrowAuthenticationServiceException(false);
            filter.setAuthenticationFailureHandler(failureHandler);
            return filter;
        }
    }
}
