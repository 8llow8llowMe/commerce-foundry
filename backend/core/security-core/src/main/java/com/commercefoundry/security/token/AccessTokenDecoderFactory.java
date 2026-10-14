package com.commercefoundry.security.token;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * access 토큰 디코더를 만든다 (서블릿). 리액티브 디코더({@code token.reactive})도 여기의 키 묶음과 validator 를 그대로 쓴다 —
 * 서비스와 게이트웨이의 판정이 갈라지지 않게 조합을 한 곳에 둔다.
 *
 * <ul>
 *   <li>서명: RS256 만. 키는 현재 + 이전(교체 중) 공개키 두 슬롯이고 {@code kid} 로 고른다. {@code kid} 가 없으면 두 키를 모두 시도한다.</li>
 *   <li>Nimbus 의 claim 검증과 {@code typ} 검증은 끈다. 만료를 포함한 모든 판정을 아래 validator 가 하고,
 *       위반은 {@code JwtValidationException} 의 오류 코드로 남는다 — 만료를 예외 문구로 가려내지 않는다.</li>
 *   <li>validator: {@code typ=at+jwt}, {@code iss} 일치, {@code aud} 포함, 만료(시계 오차 허용), claim 모양
 *       ({@code sub} 양의 정수, {@code role} ∈ {@link MemberRole}, {@code jti} 있음). 위반을 모두 모아 한 번에 낸다.</li>
 * </ul>
 */
public final class AccessTokenDecoderFactory {

    private static final OAuth2Error MALFORMED_CLAIMS = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "access token claims are malformed", null);
    private static final OAuth2Error MISSING_EXPIRY = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "access token has no exp", null);
    /** 만료는 일반 {@code invalid_token} 과 구분해야 한다. 오류 코드 자체를 {@code SECURITY_002} 로 둔다. */
    private static final OAuth2Error EXPIRED = new OAuth2Error(SecurityErrorCode.TOKEN_EXPIRED.getCode(), "access token expired", null);

    private AccessTokenDecoderFactory() {
    }

    public static JwtDecoder create(JwtVerificationProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor(verificationKeys(properties)));
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    /** 현재 공개키(필수)와 이전 공개키(있을 때만). 같은 키가 두 번 들어오면 하나로 둔다. */
    public static JWKSet verificationKeys(JwtVerificationProperties properties) {
        List<JWK> keys = new ArrayList<>();
        RSAKey current = RsaKeys.toJwk(RsaKeys.loadPublicKey(properties.publicKey(), RsaKeys.Slot.PUBLIC_KEY));
        keys.add(current);
        if (!RsaKeys.isAbsent(properties.previousPublicKey())) {
            RSAKey previous = RsaKeys.toJwk(RsaKeys.loadPublicKey(properties.previousPublicKey(), RsaKeys.Slot.PREVIOUS_PUBLIC_KEY));
            if (!previous.getKeyID().equals(current.getKeyID())) {
                keys.add(previous);
            }
        }
        return new JWKSet(keys);
    }

    /** 서명만 확인하는 Nimbus 처리기. claim · {@code typ} 판정은 {@link #validator} 몫이다. */
    public static JWTProcessor<SecurityContext> jwtProcessor(JWKSet keys) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(keys)));
        // Nimbus 기본 typ 검증은 JWT · 없음만 허용해 at+jwt 를 거부한다. typ 는 validator 가 본다
        processor.setJWSTypeVerifier((type, context) -> {
        });
        // Nimbus 기본 claim 검증은 만료를 "Expired JWT" 문구의 예외로 먼저 던진다. 만료는 validator 의 오류 코드로 판정한다
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        return processor;
    }

    public static OAuth2TokenValidator<Jwt> validator(JwtVerificationProperties properties, Clock clock) {
        JwtTypeValidator type = new JwtTypeValidator(AccessTokenFormat.ACCEPTED_TYPES);
        type.setAllowEmpty(false);
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
            type,
            new JwtIssuerValidator(properties.issuer()),
            new JwtAudienceValidator(properties.audience()),
            expiry(clock, properties.clockSkew()),
            AccessTokenDecoderFactory::claimShape);
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static OAuth2TokenValidator<Jwt> expiry(Clock clock, Duration clockSkew) {
        return jwt -> {
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null) {
                return OAuth2TokenValidatorResult.failure(MISSING_EXPIRY);
            }
            if (clock.instant().minus(clockSkew).isAfter(expiresAt)) {
                return OAuth2TokenValidatorResult.failure(EXPIRED);
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static OAuth2TokenValidatorResult claimShape(Jwt jwt) {
        boolean valid = isPositiveLong(jwt.getSubject())
            && isMemberRole(jwt.getClaims().get(MemberRole.CLAIM_NAME))
            && jwt.getId() != null && !jwt.getId().isBlank();
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(MALFORMED_CLAIMS);
    }

    /** 발급기가 {@code String.valueOf(long)} 로 쓰므로 그 모양만 받는다 ({@code +1}, {@code 007} 은 거부). */
    private static boolean isPositiveLong(String subject) {
        if (subject == null) {
            return false;
        }
        try {
            long value = Long.parseLong(subject);
            return value > 0 && String.valueOf(value).equals(subject);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean isMemberRole(Object claim) {
        if (!(claim instanceof String role)) {
            return false;
        }
        try {
            MemberRole.fromClaim(role);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
