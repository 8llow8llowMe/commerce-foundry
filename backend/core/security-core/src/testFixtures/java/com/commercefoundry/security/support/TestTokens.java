package com.commercefoundry.security.support;

import com.commercefoundry.security.issuer.JwtIssuanceProperties;
import com.commercefoundry.security.issuer.JwtIssuer;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.RsaKeys;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 발급기로 만든 정상 토큰과, 발급기가 만들지 않는 모양(다른 키 · 다른 iss · typ 없음 · alg none 등)을 Nimbus 로 직접 서명한 토큰.
 */
public final class TestTokens {

    public static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    public static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final String ISSUER = "commerce-foundry-auth";
    public static final String AUDIENCE = "commerce-foundry-api";
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);
    public static final long MEMBER_ID = 4611686018427387904L;

    private TestTokens() {
    }

    /** 현재 키 + 이전 키(교체 중) 두 슬롯. */
    public static JwtVerificationProperties verification() {
        return new JwtVerificationProperties(ISSUER, AUDIENCE, CLOCK_SKEW, TestKeys.publicKeyPem(TestKeys.CURRENT), TestKeys.publicKeyLine(TestKeys.PREVIOUS));
    }

    public static JwtVerificationProperties verificationWithoutPrevious() {
        return new JwtVerificationProperties(ISSUER, AUDIENCE, CLOCK_SKEW, TestKeys.publicKeyLine(TestKeys.CURRENT), "");
    }

    public static JwtIssuanceProperties issuance(KeyPair keyPair) {
        return new JwtIssuanceProperties(TestKeys.privateKeyLine(keyPair), ACCESS_TOKEN_TTL, Duration.ofDays(14));
    }

    public static JwtIssuer issuer(Instant issuedAt) {
        return JwtIssuer.create(verification(), issuance(TestKeys.CURRENT), Clock.fixed(issuedAt, ZoneOffset.UTC));
    }

    public static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    // ── 직접 서명 ────────────────────────────────────────────────

    /** 발급기와 같은 claim. 테스트가 하나씩 바꿔 위반을 만든다. */
    public static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .subject(String.valueOf(MEMBER_ID))
            .claim("role", MemberRole.CUSTOMER.name())
            .issueTime(Date.from(NOW))
            .expirationTime(Date.from(NOW.plus(ACCESS_TOKEN_TTL)))
            .jwtID(UUID.randomUUID().toString());
    }

    /** 발급기와 같은 헤더 — RS256 · {@code typ=at+jwt} · 썸프린트 {@code kid}. */
    public static JWSHeader.Builder header(KeyPair keyPair) {
        return new JWSHeader.Builder(JWSAlgorithm.RS256)
            .type(new JOSEObjectType("at+jwt"))
            .keyID(kid(keyPair));
    }

    public static String kid(KeyPair keyPair) {
        return RsaKeys.toJwk(TestKeys.publicKey(keyPair)).getKeyID();
    }

    public static String sign(KeyPair keyPair, JWSHeader header, JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(TestKeys.privateKey(keyPair)));
        } catch (JOSEException failure) {
            throw new IllegalStateException(failure);
        }
        return jwt.serialize();
    }

    public static String sign(KeyPair keyPair, JWTClaimsSet claims) {
        return sign(keyPair, header(keyPair).build(), claims);
    }

    /** 공개키 바이트를 HMAC 비밀로 쓴 HS256 — 알고리즘 혼동 공격의 고전적인 모양. */
    public static String signHs256WithPublicKey(JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt")).build(), claims);
        try {
            jwt.sign(new MACSigner(TestKeys.CURRENT.getPublic().getEncoded()));
        } catch (JOSEException failure) {
            throw new IllegalStateException(failure);
        }
        return jwt.serialize();
    }

    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(new PlainHeader.Builder().type(new JOSEObjectType("at+jwt")).build(), claims).serialize();
    }

    /**
     * 판정이 갈리는 토큰 모음. {@link #NOW} + 1분 시계로 검증한다. 이름이 {@code accepted-} 로 시작하면 통과해야 하는 토큰이다.
     */
    public static Map<String, String> catalog() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("accepted-issued", issuer(NOW).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER).tokenValue());
        tokens.put("accepted-issued-admin", issuer(NOW).issueAccessToken(MEMBER_ID, MemberRole.ADMIN).tokenValue());
        tokens.put("accepted-previous-key", sign(TestKeys.PREVIOUS, claims().build()));
        tokens.put("accepted-no-kid-current-key", sign(TestKeys.CURRENT, header(TestKeys.CURRENT).keyID(null).build(), claims().build()));
        tokens.put("accepted-no-kid-previous-key", sign(TestKeys.PREVIOUS, header(TestKeys.PREVIOUS).keyID(null).build(), claims().build()));
        tokens.put("expired", issuer(NOW.minus(Duration.ofHours(2))).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER).tokenValue());
        tokens.put("other-key-with-current-kid", sign(TestKeys.OTHER, header(TestKeys.CURRENT).build(), claims().build()));
        tokens.put("other-key-with-own-kid", sign(TestKeys.OTHER, claims().build()));
        tokens.put("other-key-without-kid", sign(TestKeys.OTHER, header(TestKeys.OTHER).keyID(null).build(), claims().build()));
        tokens.put("wrong-issuer", sign(TestKeys.CURRENT, claims().issuer("someone-else").build()));
        tokens.put("wrong-audience", sign(TestKeys.CURRENT, claims().audience("another-api").build()));
        tokens.put("no-typ", sign(TestKeys.CURRENT, header(TestKeys.CURRENT).type(null).build(), claims().build()));
        tokens.put("typ-jwt", sign(TestKeys.CURRENT, header(TestKeys.CURRENT).type(JOSEObjectType.JWT).build(), claims().build()));
        tokens.put("sub-not-number", sign(TestKeys.CURRENT, claims().subject("member-42").build()));
        tokens.put("role-unknown", sign(TestKeys.CURRENT, claims().claim("role", "SUPER_ADMIN").build()));
        tokens.put("role-list", sign(TestKeys.CURRENT, claims().claim("role", List.of("ADMIN")).build()));
        tokens.put("no-jti", sign(TestKeys.CURRENT, claims().jwtID(null).build()));
        tokens.put("no-exp", sign(TestKeys.CURRENT, claims().expirationTime(null).build()));
        tokens.put("alg-none", unsigned(claims().build()));
        tokens.put("hs256-public-key-secret", signHs256WithPublicKey(claims().build()));
        tokens.put("expired-and-wrong-audience", sign(TestKeys.CURRENT, claims()
            .audience("another-api").issueTime(Date.from(NOW.minus(Duration.ofHours(2)))).expirationTime(Date.from(NOW.minus(Duration.ofHours(1)))).build()));
        tokens.put("garbage", "not-a-jwt");
        return tokens;
    }
}
