package com.commercefoundry.security.token.reactive;

import com.commercefoundry.security.token.AccessTokenDecoderFactory;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.JWTProcessor;
import java.time.Clock;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이(WebFlux, #4)용 access 토큰 디코더. 키 묶음 · Nimbus 처리기 · validator 는 서블릿과 같은
 * {@link AccessTokenDecoderFactory} 의 것을 쓴다 — 같은 토큰이면 서비스와 게이트웨이가 같은 코드로 판정한다.
 *
 * <p>reactor 는 이 모듈이 끌고 가지 않는다(compileOnly). 이 클래스는 reactor 가 있는 게이트웨이에서만 쓴다.
 */
public final class ReactiveAccessTokenDecoderFactory {

    private ReactiveAccessTokenDecoderFactory() {
    }

    public static ReactiveJwtDecoder create(JwtVerificationProperties properties, Clock clock) {
        JWTProcessor<SecurityContext> processor = AccessTokenDecoderFactory.jwtProcessor(AccessTokenDecoderFactory.verificationKeys(properties));
        NimbusReactiveJwtDecoder decoder = new NimbusReactiveJwtDecoder((JWT jwt) -> Mono.fromCallable(() -> process(processor, jwt)));
        decoder.setJwtValidator(AccessTokenDecoderFactory.validator(properties, clock));
        return decoder;
    }

    /**
     * 서블릿 {@code NimbusJwtDecoder} 와 같은 예외 분류를 따른다 — 토큰 잘못({@link BadJOSEException})은 {@link BadJwtException}(→ 401),
     * 처리 오류({@link JOSEException})는 {@link JwtException}. 키가 메모리에 고정돼 있어 원격 조회 실패는 없다.
     */
    private static JWTClaimsSet process(JWTProcessor<SecurityContext> processor, JWT jwt) {
        try {
            return processor.process(jwt, null);
        } catch (BadJOSEException rejected) {
            throw new BadJwtException("An error occurred while attempting to decode the Jwt: " + rejected.getMessage(), rejected);
        } catch (JOSEException failure) {
            throw new JwtException("An error occurred while attempting to decode the Jwt: " + failure.getMessage(), failure);
        }
    }
}
