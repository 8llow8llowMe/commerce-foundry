package com.commercefoundry.security.internal;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * 서비스 토큰 디코더를 만든다 (서블릿). {@code resourceserver.InternalResourceServerConfigurer} 가 internal 체인 안에서만 쓴다 —
 * <b>{@code JwtDecoder} 빈으로 등록하지 않는다.</b> 사용자 체인의 {@code accessTokenDecoder} 주입과 Boot 자동설정이 빈 하나를 전제로 한다.
 *
 * <ul>
 *   <li>서명: <b>HS256 만.</b> 키는 현재 + 이전(교체 중) 비밀 두 슬롯이고, {@code kid} 가 없으므로 두 키를 모두 시도한다.
 *       RS256 사용자 access 토큰 · {@code alg=none} · 다른 HMAC 알고리즘은 서명 단계에서 거부된다.</li>
 *   <li>Nimbus 의 claim 검증과 {@code typ} 검증은 끈다. 판정은 아래 validator 가 하고 위반을 한 번에 모은다.</li>
 *   <li>validator: {@code typ=svc+jwt}, {@code aud} 에 이 서비스 ID 포함, {@code iat} · {@code exp} 필수 · 만료(시계 오차 30초) ·
 *       {@code iat} 가 미래가 아님 · 수명({@code exp − iat}) 5분 이하, {@code iss}(서비스 ID 모양) · {@code jti} 필수.</li>
 * </ul>
 *
 * <p>거부는 원인과 무관하게 401 · {@code SECURITY_007} 이다 ({@code SecurityErrorResolver#resolveServiceAuthentication}).
 */
@Slf4j
public final class InternalTokenDecoderFactory {

    private static final OAuth2Error MISSING_TIME = invalid("service token has no iat or exp");
    private static final OAuth2Error EXPIRED = invalid("service token expired");
    private static final OAuth2Error ISSUED_IN_FUTURE = invalid("service token iat is in the future");
    private static final OAuth2Error INVALID_LIFETIME = invalid("service token lifetime is not within the allowed range");
    private static final OAuth2Error MALFORMED_CLAIMS = invalid("service token claims are malformed");

    private InternalTokenDecoderFactory() {
    }

    public static JwtDecoder create(InternalTokenProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor(verificationKeys(properties)));
        decoder.setJwtValidator(validator(properties.serviceId(), clock));
        return decoder;
    }

    /**
     * 현재 비밀(필수)과 이전 비밀(있을 때만). 같은 비밀이 두 번 들어오면 하나로 둔다.
     * 이전 슬롯이 채워져 있으면 WARN 을 한 줄 남긴다 — 교체가 끝났는데 비우지 않으면 옛 비밀로 서명한 토큰이 계속 통과한다 ({@code security-guide.md} §9).
     * 로그에는 비밀이나 비밀에서 계산한 값(지문)을 남기지 않는다.
     */
    static JWKSet verificationKeys(InternalTokenProperties properties) {
        SecretKey current = properties.currentKey();
        List<JWK> keys = new ArrayList<>();
        keys.add(toJwk(current));
        Optional<SecretKey> previous = properties.previousKey();
        if (previous.isPresent()) {
            if (!MessageDigest.isEqual(previous.get().getEncoded(), current.getEncoded())) {
                keys.add(toJwk(previous.get()));
            }
            log.warn("internal service token previous secret slot is in use. clear {} once key rotation is complete",
                InternalTokenProperties.Slot.PREVIOUS_SECRET.environmentVariable());
        }
        return new JWKSet(keys);
    }

    /** 서명만 확인하는 Nimbus 처리기. claim · {@code typ} 판정은 {@link #validator} 몫이다. */
    static JWTProcessor<SecurityContext> jwtProcessor(JWKSet keys) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.HS256, new ImmutableJWKSet<>(keys)));
        // Nimbus 기본 typ 검증은 JWT · 없음만 허용해 svc+jwt 를 거부한다. typ 는 validator 가 본다
        processor.setJWSTypeVerifier((type, context) -> {
        });
        // claim 은 validator 가 본다. Nimbus 기본 검증을 켜 두면 만료가 validator 와 다른 시계 · 오차로 먼저 판정된다
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        return processor;
    }

    static OAuth2TokenValidator<Jwt> validator(String serviceId, Clock clock) {
        JwtTypeValidator type = new JwtTypeValidator(InternalTokenFormat.ACCEPTED_TYPES);
        type.setAllowEmpty(false);
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
            type,
            new JwtAudienceValidator(serviceId),
            lifetime(clock),
            InternalTokenDecoderFactory::claimShape);
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static JWK toJwk(SecretKey key) {
        return new OctetSequenceKey.Builder(key).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.HS256).build();
    }

    /**
     * 만료 · 발급 시각 · 수명. 공용 비밀을 가진 쪽이 {@code exp} 를 멀리 잡아 서명해도 수명 상한으로 거부한다 —
     * 가로챈 토큰을 다시 쓸 수 있는 시간을 발급 설정과 무관하게 묶는다.
     */
    private static OAuth2TokenValidator<Jwt> lifetime(Clock clock) {
        Duration skew = InternalTokenFormat.CLOCK_SKEW;
        return jwt -> {
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            if (issuedAt == null || expiresAt == null) {
                return OAuth2TokenValidatorResult.failure(MISSING_TIME);
            }
            Instant now = clock.instant();
            List<OAuth2Error> errors = new ArrayList<>();
            if (now.minus(skew).isAfter(expiresAt)) {
                errors.add(EXPIRED);
            }
            if (issuedAt.isAfter(now.plus(skew))) {
                errors.add(ISSUED_IN_FUTURE);
            }
            if (!expiresAt.isAfter(issuedAt) || Duration.between(issuedAt, expiresAt).compareTo(InternalTokenFormat.MAX_TOKEN_TTL) > 0) {
                errors.add(INVALID_LIFETIME);
            }
            return errors.isEmpty() ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(errors);
        };
    }

    private static OAuth2TokenValidatorResult claimShape(Jwt jwt) {
        boolean valid = InternalTokenFormat.isServiceId(jwt.getClaimAsString(JwtClaimNames.ISS))
            && jwt.getId() != null && !jwt.getId().isBlank();
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(MALFORMED_CLAIMS);
    }

    private static OAuth2Error invalid(String description) {
        return new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null);
    }
}
