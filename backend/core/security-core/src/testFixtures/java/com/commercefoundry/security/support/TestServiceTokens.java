package com.commercefoundry.security.support;

import com.commercefoundry.security.internal.InternalTokenFormat;
import com.commercefoundry.security.internal.InternalTokenIssuer;
import com.commercefoundry.security.internal.InternalTokenProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * 서비스 토큰(서비스 간 인증, {@code security-guide.md} §9) 테스트 도우미. 공개 저장소라 비밀을 커밋하지 않고 실행 중 만든 난수를 쓴다.
 * 같은 JVM 의 테스트가 공유한다. 컨텍스트 테스트의 {@code security.internal.secret} 도 {@link #SECRET} 이다 ({@link SecurityTestPropertiesInitializer}).
 */
public final class TestServiceTokens {

    /** 아래 비밀보다 먼저 초기화돼야 한다. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 현재 비밀(32바이트). */
    public static final byte[] SECRET = random(32);
    /** 키 교체 중의 이전 비밀. */
    public static final byte[] PREVIOUS_SECRET = random(32);
    /** 우리 것이 아닌 비밀 — 위조 서명용. */
    public static final byte[] OTHER_SECRET = random(32);

    /** 단위 테스트의 수신 서비스. */
    public static final String SERVICE_ID = "product-service";
    /** 단위 테스트의 호출 서비스. */
    public static final String CALLER = "order-service";
    public static final Instant NOW = TestTokens.NOW;
    public static final Duration TOKEN_TTL = Duration.ofSeconds(60);

    private TestServiceTokens() {
    }

    public static byte[] random(int bytes) {
        byte[] secret = new byte[bytes];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** 환경변수의 정본 모양 — {@code openssl rand -base64 32} 와 같은 표준 base64. */
    public static String secretLine(byte[] secret) {
        return Base64.getEncoder().encodeToString(secret);
    }

    /** 현재 비밀 + 이전 비밀(교체 중) 두 슬롯. */
    public static InternalTokenProperties properties(String serviceId) {
        return new InternalTokenProperties(serviceId, TOKEN_TTL, secretLine(SECRET), secretLine(PREVIOUS_SECRET));
    }

    public static InternalTokenProperties propertiesWithoutPrevious(String serviceId) {
        return new InternalTokenProperties(serviceId, TOKEN_TTL, secretLine(SECRET), "");
    }

    /** {@code caller} 서비스의 발급기. 서명은 {@link #SECRET} 이다. */
    public static InternalTokenIssuer issuer(String caller, Instant issuedAt) {
        return InternalTokenIssuer.create(propertiesWithoutPrevious(caller), Clock.fixed(issuedAt, ZoneOffset.UTC));
    }

    /** {@code caller} 가 지금 {@code audience} 를 부를 때 싣는 토큰. */
    public static String issue(String caller, String audience) {
        return issuer(caller, Instant.now()).issue(audience);
    }

    public static String bearer(String caller, String audience) {
        return "Bearer " + issue(caller, audience);
    }

    // ── 직접 서명 ────────────────────────────────────────────────

    /** 발급기와 같은 claim({@link #NOW} 발급, 60초). 테스트가 하나씩 바꿔 위반을 만든다. */
    public static JWTClaimsSet.Builder claims(String caller, String audience) {
        return new JWTClaimsSet.Builder()
            .issuer(caller)
            .audience(audience)
            .issueTime(Date.from(NOW))
            .expirationTime(Date.from(NOW.plus(TOKEN_TTL)))
            .jwtID(UUID.randomUUID().toString());
    }

    /** 발급기와 같은 헤더 — HS256 · {@code typ=svc+jwt} · {@code kid} 없음. */
    public static JWSHeader.Builder header() {
        return new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType(InternalTokenFormat.TYPE));
    }

    public static String sign(byte[] secret, JWSHeader header, JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException failure) {
            throw new IllegalStateException(failure);
        }
        return jwt.serialize();
    }

    public static String sign(byte[] secret, JWTClaimsSet claims) {
        return sign(secret, header().build(), claims);
    }
}
