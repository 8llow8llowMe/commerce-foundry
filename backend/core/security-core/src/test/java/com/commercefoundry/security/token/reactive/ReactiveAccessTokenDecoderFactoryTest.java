package com.commercefoundry.security.token.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.security.error.SecurityErrorResolver;
import com.commercefoundry.security.principal.MemberJwtAuthenticationConverter;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.AccessTokenDecoderFactory;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;

/**
 * 게이트웨이(#4)가 쓸 리액티브 디코더가 서블릿 디코더와 같은 판정을 내리는지 본다.
 * 각자 Spring Security 의 인증 관리자({@link JwtAuthenticationProvider} / {@link JwtReactiveAuthenticationManager})를 거친 결과를 비교한다.
 */
class ReactiveAccessTokenDecoderFactoryTest {

    private static final Clock CLOCK = TestTokens.clockAt(TestTokens.NOW.plus(Duration.ofMinutes(1)));

    @Test
    @DisplayName("같은 토큰 모음을 서블릿·리액티브 디코더로 돌리면 통과 여부 · 인증 주체 · 오류 코드가 모두 같다")
    void reactiveDecoderJudgesLikeServletDecoder() {
        JwtAuthenticationProvider servlet = new JwtAuthenticationProvider(AccessTokenDecoderFactory.create(TestTokens.verification(), CLOCK));
        servlet.setJwtAuthenticationConverter(new MemberJwtAuthenticationConverter());
        JwtReactiveAuthenticationManager reactive = new JwtReactiveAuthenticationManager(ReactiveAccessTokenDecoderFactory.create(TestTokens.verification(), CLOCK));
        reactive.setJwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(new MemberJwtAuthenticationConverter()));

        Map<String, String> catalog = TestTokens.catalog();
        catalog.forEach((name, token) -> {
            String servletOutcome = outcome(() -> servlet.authenticate(new BearerTokenAuthenticationToken(token)));
            String reactiveOutcome = outcome(() -> reactive.authenticate(new BearerTokenAuthenticationToken(token)).block());

            assertThat(reactiveOutcome).as(name).isEqualTo(servletOutcome);
            assertThat(servletOutcome).as(name).startsWith(name.startsWith("accepted-") ? "accepted:" : "rejected:");
        });
    }

    /**
     * 처리기 안에서 예상하지 못한 런타임 예외가 나도 서블릿과 같게 {@link BadJwtException}(→ 003)이고,
     * {@link JOSEException} 만 Bad 가 아닌 {@link JwtException}(→ 006)이다.
     */
    @Test
    @DisplayName("처리기 예외의 분류가 서블릿 NimbusJwtDecoder 와 같다 — 런타임 예외는 BadJwtException, JOSEException 은 JwtException")
    void classifiesProcessorFailuresLikeServlet() throws Exception {
        String token = TestTokens.issuer(TestTokens.NOW).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
        JWT parsed = JWTParser.parse(token);

        JWTProcessor<SecurityContext> runtimeFailure = failingProcessor(new IllegalStateException("unexpected"));
        JWTProcessor<SecurityContext> joseFailure = failingProcessor(new JOSEException("verifier unavailable"));

        Throwable servletRuntime = catchThrowable(() -> new NimbusJwtDecoder(runtimeFailure).decode(token));
        Throwable reactiveRuntime = catchThrowable(() -> ReactiveAccessTokenDecoderFactory.process(runtimeFailure, parsed));
        assertThat(servletRuntime).isExactlyInstanceOf(BadJwtException.class);
        assertThat(reactiveRuntime).isExactlyInstanceOf(BadJwtException.class);

        Throwable servletJose = catchThrowable(() -> new NimbusJwtDecoder(joseFailure).decode(token));
        Throwable reactiveJose = catchThrowable(() -> ReactiveAccessTokenDecoderFactory.process(joseFailure, parsed));
        assertThat(servletJose).isExactlyInstanceOf(JwtException.class);
        assertThat(reactiveJose).isExactlyInstanceOf(JwtException.class);
    }

    private static JWTProcessor<SecurityContext> failingProcessor(Exception failure) {
        return new DefaultJWTProcessor<>() {
            @Override
            public JWTClaimsSet process(JWT jwt, SecurityContext context) throws JOSEException {
                if (failure instanceof JOSEException jose) {
                    throw jose;
                }
                throw (RuntimeException) failure;
            }
        };
    }

    private static String outcome(AuthenticationCall call) {
        try {
            Authentication authentication = call.authenticate();
            return "accepted:" + authentication.getPrincipal();
        } catch (AuthenticationException rejected) {
            return "rejected:" + SecurityErrorResolver.resolve(rejected).getCode();
        }
    }

    @FunctionalInterface
    private interface AuthenticationCall {

        Authentication authenticate();
    }
}
