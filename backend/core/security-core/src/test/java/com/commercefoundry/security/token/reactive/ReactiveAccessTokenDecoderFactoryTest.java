package com.commercefoundry.security.token.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.error.SecurityErrorResolver;
import com.commercefoundry.security.principal.MemberJwtAuthenticationConverter;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.AccessTokenDecoderFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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
